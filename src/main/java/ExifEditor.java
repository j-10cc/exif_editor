import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExifEditor {

    private static final String DEFAULT_DIRECTORY = "D:\\Pictures\\生活\\2026.10 珠海";
    private static final String MODIFY_OPERATION = "modify";
    private static final DateTimeFormatter INPUT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter EXIF_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
    private static final Pattern EXIF_TIME_PATTERN =
            Pattern.compile("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}");

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            printMenu();
            String option = scanner.nextLine().trim();

            switch (option) {
                case "1" -> runTimeOffsetMode(scanner);
                case "2" -> runFileTimeMode(scanner);
                case "3" -> runExifCopyMode(scanner);
                case "0" -> System.out.println("已退出。");
                default -> System.out.println("无效选项，请输入 0、1、2 或 3。");
            }
        } catch (DateTimeParseException e) {
            System.out.println("时间格式错误，请使用 yyyy-MM-dd HH:mm:ss。");
        } catch (Exception e) {
            System.out.println("执行失败：" + errorMessage(e));
        }
    }

    private static void printMenu() {
        System.out.println("请选择操作：");
        System.out.println("1. 按参考时间差批量校正 EXIF 拍摄时间");
        System.out.println("2. 将文件最后修改时间写入 EXIF 拍摄时间");
        System.out.println("3. 将 A 目录下同名文件的完整 EXIF 写入 B 目录文件");
        System.out.println("0. 退出");
        System.out.print("请输入选项：");
    }

    private static void runTimeOffsetMode(Scanner scanner) {
        File directory = readDirectory(scanner, "请输入图片目录", DEFAULT_DIRECTORY);
        if (directory == null) {
            return;
        }

        System.out.print("请输入新参考时间 B（格式：yyyy-MM-dd HH:mm:ss）：");
        LocalDateTime targetTime = LocalDateTime.parse(scanner.nextLine().trim(), INPUT_TIME_FORMATTER);

        System.out.print("请输入原参考时间 T（格式：yyyy-MM-dd HH:mm:ss）：");
        LocalDateTime baseTime = LocalDateTime.parse(scanner.nextLine().trim(), INPUT_TIME_FORMATTER);

        boolean modify = readModifyOperation(scanner);
        BatchResult result = new BatchResult();
        for (File imageFile : listJpegFiles(directory)) {
            try {
                LocalDateTime originalTime = readOriginalTime(imageFile);
                LocalDateTime resultTime = targetTime.plus(Duration.between(baseTime, originalTime));

                if (modify) {
                    writeOriginalTime(imageFile, resultTime);
                    System.out.println("[完成] " + imageFile.getName() + " -> "
                            + resultTime.format(INPUT_TIME_FORMATTER));
                } else {
                    System.out.println("[预览] " + imageFile.getName() + " -> "
                            + resultTime.format(INPUT_TIME_FORMATTER));
                }
                result.success++;
            } catch (Exception e) {
                printFileError(imageFile, e);
                result.failed++;
            }
        }
        printSummary(result, modify);
    }

    private static void runFileTimeMode(Scanner scanner) {
        File directory = readDirectory(scanner, "请输入图片目录", DEFAULT_DIRECTORY);
        if (directory == null) {
            return;
        }

        boolean modify = readModifyOperation(scanner);
        BatchResult result = new BatchResult();
        for (File imageFile : listJpegFiles(directory)) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        imageFile.toPath(), BasicFileAttributes.class);
                LocalDateTime fileTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(attributes.lastModifiedTime().toMillis()),
                        ZoneId.systemDefault());

                if (modify) {
                    writeOriginalTime(imageFile, fileTime);
                    System.out.println("[完成] " + imageFile.getName() + " -> "
                            + fileTime.format(INPUT_TIME_FORMATTER));
                } else {
                    System.out.println("[预览] " + imageFile.getName() + " -> "
                            + fileTime.format(INPUT_TIME_FORMATTER));
                }
                result.success++;
            } catch (Exception e) {
                printFileError(imageFile, e);
                result.failed++;
            }
        }
        printSummary(result, modify);
    }

    private static void runExifCopyMode(Scanner scanner) throws IOException {
        File sourceDirectory = readDirectory(scanner, "请输入 EXIF 来源目录 A", null);
        if (sourceDirectory == null) {
            return;
        }

        File targetDirectory = readDirectory(scanner, "请输入 EXIF 写入目录 B", null);
        if (targetDirectory == null) {
            return;
        }

        if (Files.isSameFile(sourceDirectory.toPath(), targetDirectory.toPath())) {
            System.out.println("来源目录 A 和写入目录 B 不能是同一个目录。");
            return;
        }

        boolean modify = readModifyOperation(scanner);
        BatchResult result = new BatchResult();
        for (File sourceFile : listJpegFiles(sourceDirectory)) {
            File targetFile = new File(targetDirectory, sourceFile.getName());
            if (!targetFile.isFile()) {
                System.out.println("[跳过] B 目录不存在同名文件：" + sourceFile.getName());
                result.skipped++;
                continue;
            }

            try {
                TiffImageMetadata sourceExif = readExifMetadata(sourceFile);
                if (sourceExif == null) {
                    System.out.println("[跳过] 来源文件没有 EXIF：" + sourceFile.getName());
                    result.skipped++;
                    continue;
                }

                if (modify) {
                    replaceExif(targetFile, sourceExif.getOutputSet());
                    System.out.println("[完成] " + sourceFile.getName() + " -> " + targetFile.getName());
                } else {
                    System.out.println("[预览] " + sourceFile.getName() + " -> " + targetFile.getName());
                }
                result.success++;
            } catch (Exception e) {
                printFileError(sourceFile, e);
                result.failed++;
            }
        }
        printSummary(result, modify);
    }

    private static File readDirectory(Scanner scanner, String prompt, String defaultPath) {
        if (defaultPath == null) {
            System.out.print(prompt + "：");
        } else {
            System.out.print(prompt + "（直接回车使用 " + defaultPath + "）：");
        }

        String path = scanner.nextLine().trim();
        if (path.isEmpty() && defaultPath != null) {
            path = defaultPath;
        }

        File directory = new File(path);
        if (!directory.isDirectory()) {
            System.out.println("目录不存在或不是一个目录：" + directory.getAbsolutePath());
            return null;
        }
        return directory;
    }

    private static boolean readModifyOperation(Scanner scanner) {
        System.out.print("请输入操作类型（输入 modify 实际写入，其他内容仅预览）：");
        return MODIFY_OPERATION.equalsIgnoreCase(scanner.nextLine().trim());
    }

    private static File[] listJpegFiles(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() && isJpeg(file));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    private static boolean isJpeg(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static LocalDateTime readOriginalTime(File imageFile) throws Exception {
        TiffImageMetadata exifMetadata = readExifMetadata(imageFile);
        if (exifMetadata == null) {
            throw new IOException("图片没有 EXIF 信息");
        }

        TiffField originalTimeField = exifMetadata.findField(
                ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
        if (originalTimeField == null) {
            throw new IOException("EXIF 中没有 DateTimeOriginal 字段");
        }

        Matcher matcher = EXIF_TIME_PATTERN.matcher(originalTimeField.getValueDescription());
        if (!matcher.find()) {
            throw new IOException("无法识别 DateTimeOriginal："
                    + originalTimeField.getValueDescription());
        }
        return LocalDateTime.parse(matcher.group(), EXIF_TIME_FORMATTER);
    }

    private static TiffImageMetadata readExifMetadata(File imageFile) throws Exception {
        ImageMetadata metadata = Imaging.getMetadata(imageFile);
        if (metadata == null) {
            return null;
        }
        if (!(metadata instanceof JpegImageMetadata jpegMetadata)) {
            throw new IOException("不是可识别的 JPEG 图片");
        }
        return jpegMetadata.getExif();
    }

    private static void writeOriginalTime(File imageFile, LocalDateTime time) throws Exception {
        TiffImageMetadata exifMetadata = readExifMetadata(imageFile);
        TiffOutputSet outputSet = exifMetadata == null
                ? new TiffOutputSet()
                : exifMetadata.getOutputSet();

        TiffOutputDirectory exifDirectory = outputSet.getOrCreateExifDirectory();
        exifDirectory.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
        exifDirectory.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED);
        exifDirectory.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL,
                time.format(EXIF_TIME_FORMATTER));

        replaceExif(imageFile, outputSet);
    }

    private static void replaceExif(File imageFile, TiffOutputSet outputSet) throws Exception {
        Path imagePath = imageFile.toPath();
        Path parent = imagePath.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("无法确定图片所在目录");
        }

        // 临时文件与目标文件位于同一目录，替换失败时不会先删除原图。
        Path tempPath = Files.createTempFile(parent, ".exif-editor-", ".jpg");
        try {
            try (OutputStream outputStream = Files.newOutputStream(tempPath)) {
                new ExifRewriter().updateExifMetadataLossless(imageFile, outputStream, outputSet);
            }

            try {
                Files.move(tempPath, imagePath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempPath, imagePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    private static void printFileError(File file, Exception e) {
        System.out.println("[失败] " + file.getName() + "：" + errorMessage(e));
    }

    private static String errorMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void printSummary(BatchResult result, boolean modify) {
        String mode = modify ? "写入" : "预览";
        System.out.println(mode + "结束：成功 " + result.success
                + "，跳过 " + result.skipped + "，失败 " + result.failed + "。");
    }

    private static class BatchResult {
        private int success;
        private int skipped;
        private int failed;
    }
}
