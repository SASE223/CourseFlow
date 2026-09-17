# 课表流 CourseFlow

面向大学生的 Android 课表与待办应用，支持 Excel 课表导入、课表图片识别、截图/照片本地 OCR、待整理收件箱、自定义时间轴、课程拖动调整和 GitHub Release 更新检查。

## 主要功能

- 自定义每天的节数，以及每节课的开始和结束时间。
- Excel 与课表图片导入，支持手动维护课程。
- 图片信息本地识别并进入“待整理”待办。
- 长按课程或待办进行移动、调整长度和冲突检查。
- 通过 GitHub Releases 检查并下载用户确认的新版本。

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
