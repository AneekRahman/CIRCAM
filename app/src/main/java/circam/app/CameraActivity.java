package circam.app;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

public class CameraActivity extends AppCompatActivity {

    // Variables declarations
    TextureView mPreviewTextureView;
    FrameLayout mPreviewHolder;
    Button captureBtn, saveBtn;

    CameraRenderer mCameraRenderer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        // Variables initialization

        mPreviewHolder = (FrameLayout) findViewById(R.id.texture_view_holder);
        captureBtn = (Button) findViewById(R.id.capture_btn);
        saveBtn = (Button) findViewById(R.id.save_btn);

        mCameraRenderer = new CameraRenderer(this);
        mPreviewTextureView = new TextureView(this);

        mPreviewHolder.addView(mPreviewTextureView);
        mPreviewTextureView.setSurfaceTextureListener(mCameraRenderer);
        mCameraRenderer.setSelectedFilter(0);

        captureBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

            mCameraRenderer.startEncoding();

            }
        });
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

            mCameraRenderer.stopEncoding();

            }
        });

    }
}
