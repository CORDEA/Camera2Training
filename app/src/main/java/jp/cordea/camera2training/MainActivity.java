package jp.cordea.camera2training;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.widget.Button;

import butterknife.BindString;
import butterknife.BindView;
import butterknife.ButterKnife;

public class MainActivity extends AppCompatActivity {

    @BindView(R.id.toolbar)
    Toolbar toolbar;

    @BindView(R.id.step1_button)
    Button step1Button;

    @BindView(R.id.step2_button)
    Button step2Button;

    @BindView(R.id.step3_button)
    Button step3Button;

    @BindView(R.id.step4_button)
    Button step4Button;

    @BindView(R.id.step5_button)
    Button step5Button;

    @BindView(R.id.step6_button)
    Button step6Button;

    @BindString(R.string.title_format_text)
    String titleFormatText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        toolbar.setTitle(String.format(titleFormatText, ""));
        setSupportActionBar(toolbar);

        Context context = this;

        step1Button.setOnClickListener(view ->
                startActivity(new Intent(context, jp.cordea.camera2training.step1.CameraActivity.class)));

        step2Button.setOnClickListener(view ->
                startActivity(new Intent(context, jp.cordea.camera2training.step2.CameraActivity.class)));

        step3Button.setOnClickListener(view ->
                startActivity(new Intent(context, jp.cordea.camera2training.step3.CameraActivity.class)));

        step4Button.setOnClickListener(view ->
                startActivity(new Intent(context, jp.cordea.camera2training.step4.CameraActivity.class)));

        step5Button.setOnClickListener(view ->
                startActivity(new Intent(context, jp.cordea.camera2training.step5.CameraActivity.class)));

        step6Button.setOnClickListener(view ->
                startActivity(new Intent(context, jp.cordea.camera2training.step6.CameraActivity.class)));
    }
}
