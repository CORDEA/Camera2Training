package jp.cordea.camera2training;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

/**
 * アスペクト比に合わせて縦幅を調整する TextureView
 */
public class AspectFitTextureView extends TextureView {

    private float aspect = 0f;

    public void setAspect(float aspect) {
        this.aspect = aspect;
        requestLayout();
    }

    public AspectFitTextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (aspect != 0f) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, (int) (width * aspect));
        }
    }
}
