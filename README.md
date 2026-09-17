# 课表流 CourseFlow

面向大学生的 Android 课表与待办应用，支持 Excel 课表导入、课表图片识别、截图/照片本地 OCR、待整理收件箱、自定义时间轴、课程拖动调整和 GitHub Release 更新检查。

## 主要功能

- 自定义每天的节数，以及每节课的开始和结束时间。
- Excel 与课表图片导入，支持手动维护课程。
- 图片信息本地识别并进入“待整理”待办。
- 长按课程或待办进行移动、调整长度和冲突检查。
- 启动后通过 GitHub Releases 检查更新，并在“设置”的第一项提供手动检查入口。
- 支持普通更新、最低兼容版本和不可跳过的强制更新；安装仍由 Android 系统确认。

## 隐私说明

- 课表、待办、图片识别文字均保存在手机本地。
- OCR 使用随 APK 内置的中文识别模型，不上传图片。
- 联网仅用于访问 `SASE223/CourseFlow` 的公开 GitHub Releases，检查版本并下载用户确认的 APK。
- 应用不会静默安装更新，安装前仍需 Android 系统确认。

## 安装

请从 [最新 Release](https://github.com/SASE223/CourseFlow/releases/latest) 下载 `CourseFlow-vX.Y.Z.apk`，无需解压，直接在 Android 手机上安装。

## 发布约定

1. 更新 `app/build.gradle` 中的 `versionCode` 和 `versionName`。
2. 构建并签名 Release APK。
3. 创建 `vX.Y.Z` 标签和同名 GitHub Release。
4. 上传名为 `CourseFlow-vX.Y.Z.apk` 的 APK，并填写面向用户的更新说明。

应用通过 GitHub Releases API 读取最新的非草稿、非预发布版本。

Release 正文可加入以下独立指令行；它们不会显示在应用内的更新说明中：

- `force_update=true`：所有旧版本必须更新。
- `min_supported_version=4.0.0`：低于指定版本的用户必须更新。

跨大版本升级（例如 4.x 升级到 5.x）也会自动按强制更新处理。Android 不允许应用静默安装 APK，用户仍需在系统安装界面确认。v4.0.0 之前的版本不理解上述指令，需要先手动安装一次 v4.0.0。
