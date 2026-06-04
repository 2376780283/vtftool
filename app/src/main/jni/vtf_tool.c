#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <png.h>
#include <sys/mman.h>
#include <jni.h>
#include <android/bitmap.h>
#include <sys/stat.h>
#include <android/log.h>

#define LOG_TAG "VTF_TOOL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

enum { IMAGE_FORMAT_NONE = -1, IMAGE_FORMAT_RGBA8888 = 0, IMAGE_FORMAT_ABGR8888, IMAGE_FORMAT_RGB888, IMAGE_FORMAT_BGR888, IMAGE_FORMAT_RGB565, IMAGE_FORMAT_I8, IMAGE_FORMAT_IA88, IMAGE_FORMAT_P8, IMAGE_FORMAT_A8, IMAGE_FORMAT_RGB888_BLUESCREEN, IMAGE_FORMAT_BGR888_BLUESCREEN, IMAGE_FORMAT_ARGB8888, IMAGE_FORMAT_BGRA8888, IMAGE_FORMAT_DXT1, IMAGE_FORMAT_DXT3, IMAGE_FORMAT_DXT5, IMAGE_FORMAT_BGRX8888, IMAGE_FORMAT_BGR565, IMAGE_FORMAT_BGRX5551, IMAGE_FORMAT_BGRA4444, IMAGE_FORMAT_DXT1_ONEBITALPHA, IMAGE_FORMAT_BGRA5551, IMAGE_FORMAT_UV88, IMAGE_FORMAT_UVWQ8888, IMAGE_FORMAT_RGBA16161616F, IMAGE_FORMAT_RGBA16161616, IMAGE_FORMAT_UVLX8888 };

#pragma pack(1)
typedef struct { char signature[4]; unsigned int version[2]; unsigned int header_size; unsigned short width; unsigned short height; unsigned int flags; unsigned short frames; unsigned short first_frame; unsigned char padding0[4]; float reflectivity[3]; unsigned char padding1[4]; float bumpmap_scale; int image_format; unsigned char mipmap_count; unsigned int low_image_format; unsigned char low_width; unsigned char low_height; unsigned short depth; unsigned char padding2[3]; unsigned int numResources; unsigned char padding3[8]; } vtf_header_t;
typedef struct { unsigned char tag[3]; unsigned char flags; unsigned int offset; } vtf_resurce_entry_t;
#pragma pack(0)

static const char* get_string(JNIEnv* env, jstring jstr) { return (*env)->GetStringUTFChars(env, jstr, NULL); }
static void release_string(JNIEnv* env, jstring jstr, const char* str) { (*env)->ReleaseStringUTFChars(env, jstr, str); }

static void decode_rgba(vtf_header_t* header, uint8_t *data, uint8_t** rgba_rows) {
  int pos = 0;
  for(int y = 0; y < header->height; ++y) {
    for(int x = 0; x < header->width; ++x) {
      uint8_t r=255,g=255,b=255,a=255;
      switch(header->image_format) {
        case IMAGE_FORMAT_RGBA8888: r = data[pos++]; g = data[pos++]; b = data[pos++]; a = data[pos++]; break;
        case IMAGE_FORMAT_ARGB8888: a = data[pos++]; r = data[pos++]; g = data[pos++]; b = data[pos++]; break;
        case IMAGE_FORMAT_ABGR8888: a = data[pos++]; b = data[pos++]; g = data[pos++]; r = data[pos++]; break;
        case IMAGE_FORMAT_BGRA8888: b = data[pos++]; g = data[pos++]; r = data[pos++]; a = data[pos++]; break;
        case IMAGE_FORMAT_RGB888:   r = data[pos++]; g = data[pos++]; b = data[pos++]; break;
        case IMAGE_FORMAT_BGR888:   b = data[pos++]; g = data[pos++]; r = data[pos++]; break;
      }
      rgba_rows[y][4*x+0] = r; rgba_rows[y][4*x+1] = g; rgba_rows[y][4*x+2] = b; rgba_rows[y][4*x+3] = a;
    }
  }
}

static void write_png(const char* path, int width, int height, uint8_t** rgba_rows) {
    FILE* of = fopen(path, "wb");
    if(!of) return;
    png_structp png_ptr = png_create_write_struct(PNG_LIBPNG_VER_STRING, NULL, NULL, NULL);
    png_infop info_ptr = png_create_info_struct(png_ptr);
    png_init_io(png_ptr, of);
    png_set_IHDR(png_ptr, info_ptr, width, height, 8, PNG_COLOR_TYPE_RGB_ALPHA, PNG_INTERLACE_NONE, PNG_COMPRESSION_TYPE_BASE, PNG_FILTER_TYPE_BASE);
    png_write_info(png_ptr, info_ptr);
    png_write_image(png_ptr, rgba_rows);
    png_write_end(png_ptr, NULL);
    fclose(of);
    png_destroy_write_struct(&png_ptr, &info_ptr);
}

JNIEXPORT jboolean JNICALL Java_zzh_bin_valvevtftool_VtfLib_vtfToPng(JNIEnv* env, jobject obj, jstring vtfPath, jstring pngPath) {
    const char* vPath = get_string(env, vtfPath);
    const char* pPath = get_string(env, pngPath);
    int fd = open(vPath, O_RDONLY);
    if (fd < 0) { release_string(env, vtfPath, vPath); release_string(env, pngPath, pPath); return JNI_FALSE; }
    struct stat st; fstat(fd, &st); size_t filesize = st.st_size;
    uint8_t* filedata = mmap(0, filesize, PROT_READ, MAP_PRIVATE, fd, 0);
    vtf_header_t* header = (vtf_header_t*)filedata;
    LOGI("VTF: %dx%d, format: %d", header->width, header->height, header->image_format);
    uint8_t** rgba_rows = malloc(sizeof(uint8_t*)*header->height);
    for(int i = 0; i < header->height; ++i) rgba_rows[i] = malloc(header->width * 4);
    decode_rgba(header, (uint8_t*)filedata + header->header_size, rgba_rows);
    write_png(pPath, header->width, header->height, rgba_rows);
    for(int i = 0; i < header->height; ++i) free(rgba_rows[i]);
    free(rgba_rows); munmap(filedata, filesize); close(fd);
    release_string(env, vtfPath, vPath); release_string(env, pngPath, pPath);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_zzh_bin_valvevtftool_VtfLib_pngToVtf(JNIEnv* env, jobject obj, jstring pngPath, jstring vtfPath) {
    const char* path = get_string(env, vtfPath);
    FILE* f = fopen(path, "wb");
    if (f) fclose(f);
    release_string(env, vtfPath, path);
    return JNI_TRUE;
}
JNIEXPORT jobject JNICALL Java_zzh_bin_valvevtftool_VtfLib_getVtfBitmap(JNIEnv* env, jobject obj, jstring vtfPath) { return NULL; }
