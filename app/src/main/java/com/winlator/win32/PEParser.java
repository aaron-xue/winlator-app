package com.winlator.win32;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.winlator.core.ImageUtils;
import com.winlator.core.StreamUtils;
import com.winlator.core.StringUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Stack;

public class PEParser {
    private static final byte RT_ICON = 3;
    private final File peFile;
    private int resourcesRVA = 0;
    private int resourcesOffset = 0;

    private interface ImageResourceEntry {}

    private static class ImageResourceDirectoryEntry implements ImageResourceEntry {
        private final int name;
        private final boolean nameIsString;
        private final int offsetToData;
        private final boolean dataIsDirectory;
        private ImageResourceDirectory directory;

        private ImageResourceDirectoryEntry(ByteBuffer data) {
            int field1 = data.getInt();
            int field2 = data.getInt();

            this.name = field1 & 0x7fffffff;
            this.nameIsString = ((field1 >> 31) & 0x1) != 0;
            this.offsetToData = field2 & 0x7fffffff;
            this.dataIsDirectory = ((field2 >> 31) & 0x1) != 0;
        }
    }

    private static class ImageResourceDataEntry implements ImageResourceEntry {
        private final int offsetToData;
        private final int size;
        private final int codePage;
        private final int reserved;

        private ImageResourceDataEntry(ByteBuffer data) {
            this.offsetToData = data.getInt();
            this.size = data.getInt();
            this.codePage = data.getInt();
            this.reserved = data.getInt();
        }
    }

    private static class ImageResourceDirectory {
        private final int characteristics;
        private final int timeDateStamp;
        private final short majorVersion;
        private final short minorVersion;
        private final short numberOfNamedEntries;
        private final short numberOfIdEntries;
        private final ArrayList<ImageResourceEntry> entries = new ArrayList<>();

        private ImageResourceDirectory(ByteBuffer data, int level) {
            characteristics = data.getInt();
            timeDateStamp = data.getInt();
            majorVersion = data.getShort();
            minorVersion = data.getShort();
            numberOfNamedEntries = data.getShort();
            numberOfIdEntries = data.getShort();

            int numberOfEntries = numberOfNamedEntries + numberOfIdEntries;
            for (int i = 0; i < numberOfEntries; i++) {
                ImageResourceDirectoryEntry directoryEntry = new ImageResourceDirectoryEntry(data);

                if ((directoryEntry.name == RT_ICON && directoryEntry.dataIsDirectory) || (level > 0 && directoryEntry.dataIsDirectory)) {
                    int oldPosition = data.position();
                    data.position(directoryEntry.offsetToData);
                    directoryEntry.directory = new ImageResourceDirectory(data, level + 1);
                    data.position(oldPosition);

                    entries.add(0, directoryEntry);
                }
                else if (level > 0) {
                    int oldPosition = data.position();
                    data.position(directoryEntry.offsetToData);
                    ImageResourceDataEntry dataEntry = new ImageResourceDataEntry(data);
                    data.position(oldPosition);

                    entries.add(0, dataEntry);
                }
            }
        }
    }

    private PEParser(File peFile) {
        this.peFile = peFile;
    }

    private ByteBuffer readIconData(int iconOffset, int iconSize) {
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(peFile), StreamUtils.BUFFER_SIZE)) {
            byte[] iconBytes = new byte[iconSize];
            StreamUtils.skip(inStream, iconOffset);
            int bytesRead = inStream.read(iconBytes);

            return bytesRead != -1 ? ByteBuffer.wrap(iconBytes).order(ByteOrder.LITTLE_ENDIAN) : null;
        }
        catch (IOException e) {
            return null;
        }
    }

    private ImageResourceDirectory readImageResourceDirectory() {
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(peFile), StreamUtils.BUFFER_SIZE)) {
            int filePosition = 0;

            ByteBuffer dosHeader = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            filePosition += inStream.read(dosHeader.array());

            short magicNumber = dosHeader.getShort();
            if (magicNumber != 0x5a4d) return null;

            dosHeader.position(60);
            int fileHeaderOffset = dosHeader.getInt() + 4;

            filePosition += StreamUtils.skip(inStream, fileHeaderOffset - filePosition);

            ByteBuffer fileHeader = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            filePosition += inStream.read(fileHeader.array());

            int machine = Short.toUnsignedInt(fileHeader.getShort());
            short numberOfSections = fileHeader.getShort();

            fileHeader.position(fileHeader.position() + 12);
            short sizeofOptionalHeader = fileHeader.getShort();

            filePosition += StreamUtils.skip(inStream, sizeofOptionalHeader);

            resourcesRVA = 0;
            resourcesOffset = 0;
            int resourcesSize = 0;

            ByteBuffer sectionHeader = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
            byte[] nameBytes = new byte[8];

            for (byte i = 0; i < numberOfSections; i++) {
                sectionHeader.position(0);
                filePosition += inStream.read(sectionHeader.array());

                sectionHeader.get(nameBytes);
                String name = StringUtils.fromANSIString(nameBytes);

                if (name.equals(".rsrc")) {
                    sectionHeader.getInt();
                    resourcesRVA = sectionHeader.getInt();
                    resourcesSize = sectionHeader.getInt();
                    resourcesOffset = sectionHeader.getInt();
                    break;
                }
            }

            if (resourcesOffset > 0) {
                filePosition += StreamUtils.skip(inStream, resourcesOffset - filePosition);

                ByteBuffer resourcesBuffer = ByteBuffer.allocate(resourcesSize).order(ByteOrder.LITTLE_ENDIAN);
                inStream.read(resourcesBuffer.array(), 0, resourcesBuffer.limit());

                return new ImageResourceDirectory(resourcesBuffer, 0);
            }

            return null;
        }
        catch (IOException e) {
            return null;
        }
    }

    /**
     * 优化的图标提取方法，总是返回最高质量的图标
     * 策略：
     * 1. 首先尝试寻找 PNG 格式的图标（通常质量最高）
     * 2. 其次选择像素尺寸最大的 BMP 图标
     * 3. 最后选择色彩深度最高的图标
     */
    private Bitmap decodeIcon(int iconIndex, boolean largeIcon, ArrayList<ImageResourceDataEntry> dataEntries) throws IOException {
        // 存储所有有效图标的候选者
        class IconCandidate {
            int width;
            int bitCount;
            boolean isPNG;
            int index;
            ImageResourceDataEntry entry;
            Bitmap bitmap;
        }
        ArrayList<IconCandidate> candidates = new ArrayList<>();
        
        // 第一阶段：收集所有候选图标的信息
        for (int i = 0; i < dataEntries.size(); i++) {
            ImageResourceDataEntry dataEntry = dataEntries.get(i);
            int fileOffset = (dataEntry.offsetToData - this.resourcesRVA) + this.resourcesOffset;
            ByteBuffer iconData = readIconData(fileOffset, dataEntry.size);
            if (iconData != null) {
                IconCandidate candidate = new IconCandidate();
                candidate.index = i;
                candidate.entry = dataEntry;
                
                if (ImageUtils.isPNGData(iconData)) {
                    // PNG 格式优先级最高
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(iconData.array(), 0, iconData.limit(), options);
                    
                    if (options.outWidth > 0) {
                        candidate.width = options.outWidth;
                        candidate.bitCount = 32; // PNG 通常使用 32 位色彩
                        candidate.isPNG = true;
                        candidates.add(candidate);
                    }
                } else {
                    // BMP 格式
                    if (iconData.remaining() >= 40) {
                        int bitmapOffset = iconData.getInt();
                        int bmpWidth = iconData.getInt();
                        if (bmpWidth > 0 && bmpWidth <= 4096 && bitmapOffset >= 0 && bitmapOffset < iconData.limit()) {
                            // 读取位图信息头
                            iconData.getInt(); // height
                            iconData.getShort(); // planes
                            short bitCount = iconData.getShort();
                            int compression = iconData.getInt();
                            // ... 其他字段
                            
                            // 只支持未压缩的格式
                            if ((bitCount == 8 || bitCount == 24 || bitCount == 32) && compression == 0) {
                                candidate.width = bmpWidth;
                                candidate.bitCount = bitCount;
                                candidate.isPNG = false;
                                candidates.add(candidate);
                            }
                        }
                    }
                }
            }
        }
        
        // 如果请求特定的图标索引
        if (iconIndex >= 0) {
            for (IconCandidate candidate : candidates) {
                if (candidate.index == iconIndex) {
                    int fileOffset = (candidate.entry.offsetToData - this.resourcesRVA) + this.resourcesOffset;
                    ByteBuffer iconData = readIconData(fileOffset, candidate.entry.size);
                    if (iconData != null) {
                        if (candidate.isPNG) {
                            return BitmapFactory.decodeByteArray(iconData.array(), 0, iconData.limit());
                        } else {
                            int bitmapOffset = iconData.getInt();
                            iconData.getInt(); // width
                            iconData.getInt(); // height
                            iconData.getShort(); // planes
                            iconData.position(bitmapOffset);
                            return MSBitmap.decodeBuffer(candidate.width, candidate.width, candidate.bitCount, iconData);
                        }
                    }
                }
            }
            return null;
        }
        
        // 第二阶段：根据条件选择最佳候选者
        // 优先级：PNG > 宽度 > 色彩深度
        IconCandidate bestCandidate = null;
        for (IconCandidate candidate : candidates) {
            boolean sizeMatch = largeIcon ? (candidate.width >= 32) : (candidate.width < 32);
            if (!sizeMatch) continue;
            
            if (bestCandidate == null) {
                bestCandidate = candidate;
            } else {
                // PNG 优先
                if (candidate.isPNG && !bestCandidate.isPNG) {
                    bestCandidate = candidate;
                } else if (!candidate.isPNG && bestCandidate.isPNG) {
                    continue;
                }
                // 同类型下选择更宽的
                if (candidate.width > bestCandidate.width) {
                    bestCandidate = candidate;
                } else if (candidate.width == bestCandidate.width) {
                    // 相同宽度下选择更高色彩深度的
                    if (candidate.bitCount > bestCandidate.bitCount) {
                        bestCandidate = candidate;
                    }
                }
            }
        }
        
        // 如果没有找到符合尺寸要求的图标，则选择最佳候选（忽略尺寸限制）
        if (bestCandidate == null && !candidates.isEmpty()) {
            bestCandidate = candidates.get(0);
            for (IconCandidate candidate : candidates) {
                if (candidate.isPNG && !bestCandidate.isPNG) {
                    bestCandidate = candidate;
                } else if (!candidate.isPNG && bestCandidate.isPNG) {
                    continue;
                }
                if (candidate.width > bestCandidate.width ||
                    (candidate.width == bestCandidate.width && candidate.bitCount > bestCandidate.bitCount)) {
                    bestCandidate = candidate;
                }
            }
        }
        
        // 第三阶段：解码并返回最佳候选者
        if (bestCandidate != null) {
            int fileOffset = (bestCandidate.entry.offsetToData - this.resourcesRVA) + this.resourcesOffset;
            ByteBuffer iconData = readIconData(fileOffset, bestCandidate.entry.size);
            if (iconData != null) {
                if (bestCandidate.isPNG) {
                    return BitmapFactory.decodeByteArray(iconData.array(), 0, iconData.limit());
                } else {
                    int bitmapOffset = iconData.getInt();
                    iconData.getInt(); // width
                    iconData.getInt(); // height
                    iconData.getShort(); // planes
                    iconData.position(bitmapOffset);
                    return MSBitmap.decodeBuffer(bestCandidate.width, bestCandidate.width, bestCandidate.bitCount, iconData);
                }
            }
        }
        
        return null;
    }

    private Bitmap extractIcon(int iconIndex) {
        if (!peFile.isFile()) return null;

        try {
            ImageResourceDirectory rootDirectory = readImageResourceDirectory();
            if (rootDirectory == null) return null;

            ArrayList<ImageResourceDataEntry> dataEntries = new ArrayList<>();
            Stack<ImageResourceDirectory> stack = new Stack<>();
            stack.push(rootDirectory);
            while (!stack.isEmpty()) {
                ImageResourceDirectory directory = stack.pop();

                for (ImageResourceEntry entry : directory.entries) {
                    if (entry instanceof ImageResourceDirectoryEntry) {
                        stack.push(((ImageResourceDirectoryEntry)entry).directory);
                    }
                    else if (entry instanceof ImageResourceDataEntry) {
                        dataEntries.add((ImageResourceDataEntry)entry);
                    }
                }
            }

            if (iconIndex >= 0) {
                return decodeIcon(iconIndex, true, dataEntries);
            }
            else {
                Bitmap bitmap = decodeIcon(-1, true, dataEntries);
                if (bitmap != null) return bitmap;

                bitmap = decodeIcon(-1, false, dataEntries);
                if (bitmap != null) return bitmap;
            }

            return null;
        }
        catch (IOException e) {
            return null;
        }
    }

    public static Bitmap extractIcon(File peFile) {
        return extractIcon(peFile, -1);
    }

    public static Bitmap extractIcon(File peFile, int iconIndex) {
        return (new PEParser(peFile)).extractIcon(iconIndex);
    }
}
