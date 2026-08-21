# EXIF Editor

一个基于 Java 和 Apache Commons Imaging 的 JPEG 图片 EXIF 拍摄时间批量处理工具。项目提供两种处理方式：按参考照片的时间偏移量统一校正一批照片，或将文件的最后修改时间写入 EXIF 拍摄时间。

> 工具会在 `modify` 模式下替换原始图片。首次使用时请先备份照片，并使用预览模式核对结果。

## 1. 项目用途

本项目适合处理相机时间设置不正确、照片时间需要整体平移，或需要根据文件时间重建 EXIF 拍摄时间的场景。

项目包含两个可独立运行的入口：

| 入口类 | 用途 | 时间计算方式 |
| --- | --- | --- |
| `ExifEditor` | 按参考时间批量校正照片时间 | `新时间 = 新参考时间 B +（照片原 EXIF 时间 - 原参考时间 T）` |
| `ExifEditor2` | 使用文件时间重写照片时间 | 将文件的最后修改时间写入 `DateTimeOriginal` |

程序通过无损方式更新 JPEG 的 EXIF 元数据，不主动重新编码图片内容。当前实现只扫描指定目录的第一层，不递归处理子目录。

## 2. 项目结构

```text
exif_editor/
├── pom.xml
├── README.md
└── src/
    └── main/
        └── java/
            ├── ExifEditor.java
            └── ExifEditor2.java
```

- `pom.xml`：Maven 构建配置，声明 Java 20 和 Apache Commons Imaging 依赖。
- `ExifEditor.java`：根据参考时间差批量平移 JPEG 的 EXIF 拍摄时间。
- `ExifEditor2.java`：读取文件最后修改时间，并将其写入 JPEG 的 EXIF 拍摄时间。
- `target/`：执行 Maven 构建后生成的编译产物目录，不属于源代码。

## 3. 简单使用手册

### 3.1 环境要求

- JDK 20
- Maven 3.x
- 包含 EXIF 信息的 JPEG 图片

确认环境：

```powershell
java -version
mvn -version
```

### 3.2 配置图片目录

运行前需要修改所选入口类中的 `directoryPath`，将其设置为待处理图片所在目录。例如：

```java
String directoryPath = "D:\\Pictures\\待处理照片";
```

Windows 路径中的反斜杠需要写成 `\\`。程序会遍历该目录下的所有直接子项，因此建议目标目录只存放需要处理的 JPEG 图片。

### 3.3 编译项目

在项目根目录执行：

```powershell
mvn compile
```

也可以直接使用 IntelliJ IDEA 打开项目，等待 Maven 完成依赖加载后运行对应类的 `main` 方法。

### 3.4 方式一：按时间差批量校正

运行 `ExifEditor.main()` 后，程序依次要求输入：

1. 新参考时间 `B`，格式为 `yyyy-MM-dd HH:mm:ss`。
2. 原参考时间 `T`，格式为 `yyyy-MM-dd HH:mm:ss`。
3. 操作类型。

例如，原参考照片的正确基准应从 `2024-06-01 10:00:00` 调整到 `2024-06-01 10:05:00`，可输入：

```text
2024-06-01 10:05:00
2024-06-01 10:00:00
preview
```

程序会保持每张照片相对于原参考时间 `T` 的时间间隔，并把整组照片平移到新参考时间 `B`。第三项输入除 `modify` 以外的任意内容时，只打印拟写入的时间，不修改文件。

确认预览结果正确后再次运行，并在第三项输入：

```text
modify
```

### 3.5 方式二：使用文件最后修改时间

运行 `ExifEditor2.main()`，输入除 `modify` 以外的任意内容可预览每张图片拟写入的时间：

```text
preview
```

确认无误后再次运行并输入：

```text
modify
```

程序会读取每个文件的最后修改时间，并写入 EXIF 的 `DateTimeOriginal` 字段。

### 3.6 命令行运行（可选）

除 IDE 外，也可以通过 Maven Exec 插件运行入口类：

```powershell
# 按参考时间差校正
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=ExifEditor"

# 使用文件最后修改时间
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=ExifEditor2"
```

首次执行时 Maven 可能需要联网下载插件和依赖。

## 使用注意事项

- 实际修改前务必备份原始图片，并先使用预览模式检查时间。
- 只有操作类型精确输入 `modify`（不区分大小写）时才会修改图片。
- 当前实现主要面向带有 EXIF 元数据的 JPEG；其他格式、无 EXIF 图片或损坏文件可能被跳过或输出异常信息。
- 程序只处理配置目录的第一层内容，不会递归扫描子目录。
- 修改过程会先创建临时文件，再删除原文件并用临时文件替换；请确保目录可写且磁盘空间充足。
- 时间使用运行电脑的本地时区；`ExifEditor2` 的结果会受到文件最后修改时间准确性的影响。
