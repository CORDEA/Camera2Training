package jp.cordea.camera2training.step6;

/**
 * カメラの状態を表す Enum
 */
public enum StateType {
    STATE_PREVIEW,
    STATE_WAITING_LOCK,
    STATE_WAITING_PRECAPTURE,
    STATE_WAITING_NON_PRECAPTURE,
    STATE_PICTURE_TAKEN
}
