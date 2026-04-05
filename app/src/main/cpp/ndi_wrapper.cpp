#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "Processing.NDI.Lib.h"

#define LOG_TAG "NdiJniWrapper"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static NDIlib_send_instance_t pNDI_send = nullptr;
static std::vector<uint8_t> frame_buffer;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cfmapps_networkcamera_NdiCameraManager_initializeNdi(
        JNIEnv* env,
        jobject /* this */,
        jstring ndiName) {
    if (!NDIlib_initialize()) {
        LOGE("Cannot run NDI.");
        return JNI_FALSE;
    }

    const char* name = env->GetStringUTFChars(ndiName, nullptr);

    NDIlib_send_create_t NDI_send_create_desc;
    NDI_send_create_desc.p_ndi_name = name;
    NDI_send_create_desc.clock_video = true;
    NDI_send_create_desc.clock_audio = false;
    
    pNDI_send = NDIlib_send_create(&NDI_send_create_desc);

    env->ReleaseStringUTFChars(ndiName, name);

    if (!pNDI_send) {
        LOGE("Failed to create NDI sender.");
        return JNI_FALSE;
    }
    
    LOGD("NDI Sender initialized.");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cfmapps_networkcamera_NdiCameraManager_sendVideoFrame(
        JNIEnv* env,
        jobject /* this */,
        jint width, jint height,
        jobject yBuffer, jint yRowStride,
        jobject uBuffer, jint uRowStride,
        jobject vBuffer, jint vRowStride,
        jint uvPixelStride,
        jint rotation) {

    if (!pNDI_send) return;

    uint8_t* yPtr = (uint8_t*)env->GetDirectBufferAddress(yBuffer);
    uint8_t* uPtr = (uint8_t*)env->GetDirectBufferAddress(uBuffer);
    uint8_t* vPtr = (uint8_t*)env->GetDirectBufferAddress(vBuffer);

    int outWidth = (rotation == 90 || rotation == 270) ? height : width;
    int outHeight = (rotation == 90 || rotation == 270) ? width : height;

    size_t ySize = outWidth * outHeight;
    size_t uvSize = (outWidth / 2) * (outHeight / 2);
    size_t totalSize = ySize + (uvSize * 2);

    if (frame_buffer.size() != totalSize) {
        frame_buffer.resize(totalSize);
    }

    uint8_t* destY = frame_buffer.data();
    uint8_t* destU = destY + ySize;
    uint8_t* destV = destU + uvSize;

    // Y Plane
    if (rotation == 0) {
        for (int i = 0; i < height; ++i) {
            memcpy(destY + i * width, yPtr + i * yRowStride, width);
        }
    } else if (rotation == 90) {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int outX = height - 1 - y;
                int outY = x;
                destY[outY * outWidth + outX] = yPtr[y * yRowStride + x];
            }
        }
    } else if (rotation == 180) {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int outX = width - 1 - x;
                int outY = height - 1 - y;
                destY[outY * outWidth + outX] = yPtr[y * yRowStride + x];
            }
        }
    } else if (rotation == 270) {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int outX = y;
                int outY = width - 1 - x;
                destY[outY * outWidth + outX] = yPtr[y * yRowStride + x];
            }
        }
    }

    // U and V Planes
    int halfW = width / 2;
    int halfH = height / 2;
    int outHalfW = outWidth / 2;

    if (rotation == 0) {
        for (int i = 0; i < halfH; ++i) {
            for (int j = 0; j < halfW; ++j) {
                destU[i * outHalfW + j] = uPtr[i * uRowStride + j * uvPixelStride];
                destV[i * outHalfW + j] = vPtr[i * vRowStride + j * uvPixelStride];
            }
        }
    } else if (rotation == 90) {
        for (int y = 0; y < halfH; ++y) {
            for (int x = 0; x < halfW; ++x) {
                int outX = halfH - 1 - y;
                int outY = x;
                destU[outY * outHalfW + outX] = uPtr[y * uRowStride + x * uvPixelStride];
                destV[outY * outHalfW + outX] = vPtr[y * vRowStride + x * uvPixelStride];
            }
        }
    } else if (rotation == 180) {
        for (int y = 0; y < halfH; ++y) {
            for (int x = 0; x < halfW; ++x) {
                int outX = halfW - 1 - x;
                int outY = halfH - 1 - y;
                destU[outY * outHalfW + outX] = uPtr[y * uRowStride + x * uvPixelStride];
                destV[outY * outHalfW + outX] = vPtr[y * vRowStride + x * uvPixelStride];
            }
        }
    } else if (rotation == 270) {
        for (int y = 0; y < halfH; ++y) {
            for (int x = 0; x < halfW; ++x) {
                int outX = y;
                int outY = halfW - 1 - x;
                destU[outY * outHalfW + outX] = uPtr[y * uRowStride + x * uvPixelStride];
                destV[outY * outHalfW + outX] = vPtr[y * vRowStride + x * uvPixelStride];
            }
        }
    }

    NDIlib_video_frame_v2_t NDI_video_frame;
    NDI_video_frame.xres = outWidth;
    NDI_video_frame.yres = outHeight;
    NDI_video_frame.FourCC = NDIlib_FourCC_video_type_I420;
    NDI_video_frame.p_data = frame_buffer.data();
    NDI_video_frame.frame_rate_N = 30000;
    NDI_video_frame.frame_rate_D = 1001;
    NDI_video_frame.line_stride_in_bytes = outWidth;

    NDIlib_send_send_video_v2(pNDI_send, &NDI_video_frame);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cfmapps_networkcamera_NdiCameraManager_destroyNdi(
        JNIEnv* env,
        jobject /* this */) {
    if (pNDI_send) {
        NDIlib_send_destroy(pNDI_send);
        pNDI_send = nullptr;
    }
    NDIlib_destroy();
    LOGD("NDI Destroyed.");
}
