package org.joml.lwjgl;

import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.ARBFragmentShader.*;
import static org.lwjgl.opengl.ARBShaderObjects.*;
import static org.lwjgl.opengl.ARBVertexShader.*;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL11.*;
import org.lwjgl.opengl.GLCapabilities;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Same as {@link ShaderExample} but only using OpenGL 1.1 and the ARB shader extensions.
 *
 * @author Kai Burjack
 */
public class ShaderExample11 {
    GLFWErrorCallback errorCallback;
    GLFWKeyCallback keyCallback;
    GLFWFramebufferSizeCallback fbCallback;

    long window;
    int width = 300;
    int height = 300;
    Object lock = new Object();
    boolean destroyed;

    Matrix4f viewProjMatrix = new Matrix4f();
    FloatBuffer fb = BufferUtils.createFloatBuffer(16);

    void run() {
        try {
            init();
            loop();

            synchronized (lock) {
                destroyed = true;
                glfwDestroyWindow(window);
            }
            keyCallback.free();
            fbCallback.free();
        } finally {
            glfwTerminate();
            errorCallback.free();
        }
    }

    void init() {
        glfwSetErrorCallback(errorCallback = GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_SAMPLES, 8);

        window = glfwCreateWindow(width, height, "Hello shaders!", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");

        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
            }
        });
        glfwSetFramebufferSizeCallback(window, fbCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(long window, int w, int h) {
                if (w > 0 && h > 0) {
                    width = w;
                    height = h;
                }
            }
        });

        GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(window, (vidmode.width() - width) / 2, (vidmode.height() - height) / 2);

        glfwShowWindow(window);
    }

    void renderCube() {
        glBegin(GL_QUADS);
        glVertex3f(  0.5f, -0.5f, -0.5f );
        glVertex3f( -0.5f, -0.5f, -0.5f );
        glVertex3f( -0.5f,  0.5f, -0.5f );
        glVertex3f(  0.5f,  0.5f, -0.5f );

        glVertex3f(  0.5f, -0.5f,  0.5f );
        glVertex3f(  0.5f,  0.5f,  0.5f );
        glVertex3f( -0.5f,  0.5f,  0.5f );
        glVertex3f( -0.5f, -0.5f,  0.5f );

        glVertex3f(  0.5f, -0.5f, -0.5f );
        glVertex3f(  0.5f,  0.5f, -0.5f );
        glVertex3f(  0.5f,  0.5f,  0.5f );
        glVertex3f(  0.5f, -0.5f,  0.5f );

        glVertex3f( -0.5f, -0.5f,  0.5f );
        glVertex3f( -0.5f,  0.5f,  0.5f );
        glVertex3f( -0.5f,  0.5f, -0.5f );
        glVertex3f( -0.5f, -0.5f, -0.5f );

        glVertex3f(  0.5f,  0.5f,  0.5f );
        glVertex3f(  0.5f,  0.5f, -0.5f );
        glVertex3f( -0.5f,  0.5f, -0.5f );
        glVertex3f( -0.5f,  0.5f,  0.5f );

        glVertex3f(  0.5f, -0.5f, -0.5f );
        glVertex3f(  0.5f, -0.5f,  0.5f );
        glVertex3f( -0.5f, -0.5f,  0.5f );
        glVertex3f( -0.5f, -0.5f, -0.5f );
        glEnd();
    }

    void renderGrid() {
        glBegin(GL_LINES);
        for (int i = -20; i <= 20; i++) {
            glVertex3f(-20.0f, 0.0f, i);
            glVertex3f( 20.0f, 0.0f, i);
            glVertex3f(i, 0.0f, -20.0f);
            glVertex3f(i, 0.0f,  20.0f);
        }
        glEnd();
    }

    void initOpenGLAndRenderInAnotherThread() {
        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        GLCapabilities caps = GL.createCapabilities();
        if (!caps.GL_ARB_shader_objects)
            throw new UnsupportedOperationException("This demo requires the ARB_shader_objects extension");
        if (!caps.GL_ARB_vertex_shader)
            throw new UnsupportedOperationException("This demo requires the ARB_vertex_shader extension");
        if (!caps.GL_ARB_fragment_shader)
            throw new UnsupportedOperationException("This demo requires the ARB_fragment_shader extension");

        glClearColor(0.6f, 0.7f, 0.8f, 1.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);

        // Create a simple shader program
        int program = glCreateProgramObjectARB();
        int vs = glCreateShaderObjectARB(GL_VERTEX_SHADER_ARB);
        glShaderSourceARB(vs,
                "uniform mat4 viewProjMatrix;" +
                "void main(void) {" +
                "  gl_Position = viewProjMatrix * gl_Vertex;" +
                "}");
        glCompileShaderARB(vs);
        glAttachObjectARB(program, vs);
        int fs = glCreateShaderObjectARB(GL_FRAGMENT_SHADER_ARB);
        glShaderSourceARB(fs,
                "uniform vec3 color;" +
                "void main(void) {" +
                "  gl_FragColor = vec4(color, 1.0);" +
                "}");
        glCompileShaderARB(fs);
        glAttachObjectARB(program, fs);
        glLinkProgramARB(program);
        glUseProgramObjectARB(program);

        // Obtain uniform location
        int matLocation = glGetUniformLocationARB(program, "viewProjMatrix");
        int colorLocation = glGetUniformLocationARB(program, "color");
        long lastTime = System.nanoTime();

        /* Quaternion to rotate the cube */
        Quaternionf q = new Quaternionf();

        while (!destroyed) {
            long thisTime = System.nanoTime();
            float dt = (thisTime - lastTime) / 1E9f;
            lastTime = thisTime;

            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            // Create a view-projection matrix
            viewProjMatrix.setPerspective((float) Math.toRadians(30.0f),
                                          (float) width / height, 0.01f, 100.0f)
                          .lookAt(0.0f, 4.0f, 10.0f,
                                  0.0f, 0.5f, 0.0f,
                                  0.0f, 1.0f, 0.0f);
            // Upload the matrix stored in the FloatBuffer to the
            // shader uniform.
            glUniformMatrix4fvARB(matLocation, false, viewProjMatrix.get(fb));
            // Render the grid without rotating
            glUniform3fARB(colorLocation, 0.3f, 0.3f, 0.3f);
            renderGrid();

            // rotate the cube (45 degrees per second)
            // and translate it by 0.5 in y
            viewProjMatrix.translate(0.0f, 0.5f, 0.0f)
                          .rotate(q.rotateY((float) Math.toRadians(45) * dt).normalize());
            // Upload the matrix
            glUniformMatrix4fvARB(matLocation, false, viewProjMatrix.get(fb));

            // Render solid cube with outlines
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            glUniform3fARB(colorLocation, 0.6f, 0.7f, 0.8f);
            renderCube();
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
            glEnable(GL_POLYGON_OFFSET_LINE);
            glPolygonOffset(-1.f,-1.f);
            glUniform3fARB(colorLocation, 0.0f, 0.0f, 0.0f);
            renderCube();
            glDisable(GL_POLYGON_OFFSET_LINE);

            synchronized (lock) {
                if (!destroyed) {
                    glfwSwapBuffers(window);
                }
            }
        }
    }

    void loop() {
        /*
         * Spawn a new thread which to make the OpenGL context current in and which does the
         * rendering.
         */
        new Thread(new Runnable() {
            public void run() {
                try {
                    initOpenGLAndRenderInAnotherThread();
                } catch (Exception e) {
                    e.printStackTrace();
                    glfwSetWindowShouldClose(window, true);
                    glfwPostEmptyEvent();
                }
            }
        }).start();

        /* Process window messages in the main thread */
        while (!glfwWindowShouldClose(window)) {
            glfwWaitEvents();
        }
    }

    public static void main(String[] args) {
        new ShaderExample11().run();
    }
}
