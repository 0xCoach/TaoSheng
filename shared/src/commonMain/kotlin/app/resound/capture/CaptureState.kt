package app.resound.capture

enum class CaptureState {
    /** 服务没在跑 */
    Stopped,

    /** 在跑，没人说话 */
    Listening,

    /** 检测到人声，正在录这一段 */
    Recording,

    /** 用户手动暂停 */
    Paused,

    /** 麦克风被电话或其他 App 占用，等待释放后自动恢复 */
    MicBusy,

    /** 缺少麦克风或通知权限 */
    NoPermission,
}
