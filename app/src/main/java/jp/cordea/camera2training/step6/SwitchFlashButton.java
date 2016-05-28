package jp.cordea.camera2training.step6;

import android.content.Context;
import android.support.design.widget.FloatingActionButton;
import android.util.AttributeSet;
import android.view.View;

import jp.cordea.camera2training.R;

/**
 * Flash を切り替える FloatingActionButton
 */
public class SwitchFlashButton extends FloatingActionButton implements View.OnClickListener {

    private FlashType flashType = FlashType.AUTO;

    public FlashType getFlashType() {
        return flashType;
    }

    private OnSwitchFlashTypeListener listener;

    public interface OnSwitchFlashTypeListener {
        void onSwitchFlashType(FlashType flashType);
    }

    public SwitchFlashButton(Context context, AttributeSet attrs) {
        super(context, attrs);

        setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        FlashType flashType = switchFlash();
        if (listener != null) {
            listener.onSwitchFlashType(flashType);
        }
    }

    private FlashType switchFlash() {
        switch (flashType) {
            case AUTO:
                flashType = FlashType.ON;
                setImageResource(R.drawable.ic_flash_on_white_24dp);
                break;
            case ON:
                flashType = FlashType.OFF;
                setImageResource(R.drawable.ic_flash_off_white_24dp);
                break;
            case OFF:
                flashType = FlashType.AUTO;
                setImageResource(R.drawable.ic_flash_auto_white_24dp);
                break;
        }
        return flashType;
    }

    public void setOnSwitchFlashListener(OnSwitchFlashTypeListener listener) {
        this.listener = listener;
    }
}
