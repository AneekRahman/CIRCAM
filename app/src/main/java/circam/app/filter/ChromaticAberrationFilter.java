package circam.app.filter;

import android.content.Context;
import android.opengl.GLES20;

import circam.app.MyGLUtils;
import circam.app.R;


public class ChromaticAberrationFilter extends CameraFilter {
    private int program;

    public ChromaticAberrationFilter(Context context) {
        super(context);

        // Build shaders
        program = MyGLUtils.buildProgram(context, R.raw.vertext, R.raw.chromatic_aberration);
    }

    @Override
    public void onDraw(int cameraTexId, int canvasWidth, int canvasHeight) {
        setupShaderInputs(program,
                new int[]{canvasWidth, canvasHeight},
                new int[]{cameraTexId},
                new int[][]{});
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
}
