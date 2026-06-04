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

static void write_log(const char* message) {
    // Attempt to write to a likely writable location
    FILE* logFile = fopen("/sdcard/Android/data/zzh.bin.valvevtftool/files/vtf_tool.log", "a");
    if (logFile) {
        fprintf(logFile, "%s\n", message);
        fclose(logFile);
    }
}

#define LOGI(...) do { \
    char buffer[256]; \
    snprintf(buffer, sizeof(buffer), __VA_ARGS__); \
    write_log(buffer); \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", buffer); \
} while (0)

enum { IMAGE_FORMAT_NONE = -1, IMAGE_FORMAT_RGBA8888 = 0, IMAGE_FORMAT_ABGR8888, IMAGE_FORMAT_RGB888, IMAGE_FORMAT_BGR888, IMAGE_FORMAT_RGB565, IMAGE_FORMAT_I8, IMAGE_FORMAT_IA88, IMAGE_FORMAT_P8, IMAGE_FORMAT_A8, IMAGE_FORMAT_RGB888_BLUESCREEN, IMAGE_FORMAT_BGR888_BLUESCREEN, IMAGE_FORMAT_ARGB8888, IMAGE_FORMAT_BGRA8888, IMAGE_FORMAT_DXT1, IMAGE_FORMAT_DXT3, IMAGE_FORMAT_DXT5, IMAGE_FORMAT_BGRX8888, IMAGE_FORMAT_BGR565, IMAGE_FORMAT_BGRX5551, IMAGE_FORMAT_BGRA4444, IMAGE_FORMAT_DXT1_ONEBITALPHA, IMAGE_FORMAT_BGRA5551, IMAGE_FORMAT_UV88, IMAGE_FORMAT_UVWQ8888, IMAGE_FORMAT_RGBA16161616F, IMAGE_FORMAT_RGBA16161616, IMAGE_FORMAT_UVLX8888 };


#pragma pack(1)
typedef struct { char signature[4]; unsigned int version[2]; unsigned int header_size; unsigned short width; unsigned short height; unsigned int flags; unsigned short frames; unsigned short first_frame; unsigned char padding0[4]; float reflectivity[3]; unsigned char padding1[4]; float bumpmap_scale; int image_format; unsigned char mipmap_count; unsigned int low_image_format; unsigned char low_width; unsigned char low_height; unsigned short depth; unsigned char padding2[3]; unsigned int numResources; unsigned char padding3[8]; } vtf_header_t;
typedef struct { unsigned char tag[3]; unsigned char flags; unsigned int offset; } vtf_resurce_entry_t;
#pragma pack(0)

static const char* get_string(JNIEnv* env, jstring jstr) { return (*env)->GetStringUTFChars(env, jstr, NULL); }
static void release_string(JNIEnv* env, jstring jstr, const char* str) { (*env)->ReleaseStringUTFChars(env, jstr, str); }

static void rgb565_to_rgb888(uint16_t in, uint8_t *out)
{
  uint8_t r,g,b;
  r = (uint8_t) (in >> 11) & 31;
  r = (r << 3) | (r >> 2);
  g = (uint8_t) (in >>  5) & 63;
  g = (g << 2) | (g >> 4);
  b = (uint8_t) (in >>  0) & 31;
  b = (b << 3) | (b >> 2);
  out[0] = r;
  out[1] = g;
  out[2] = b;
}

static void decode_dxt_colors(int x, int y, uint16_t c0, uint16_t c1, uint32_t ci, uint8_t** rgba_rows)
{
  uint8_t c888[3], r[4], g[4], b[4];

  rgb565_to_rgb888(c0, &c888[0]);
  r[0] = c888[0];
  g[0] = c888[1];
  b[0] = c888[2];

  rgb565_to_rgb888(c1, &c888[0]);
  r[1] = c888[0];
  g[1] = c888[1];
  b[1] = c888[2];

  r[2] = (4*r[0] + 2*r[1] + 3)/6;
  g[2] = (4*g[0] + 2*g[1] + 3)/6;
  b[2] = (4*b[0] + 2*b[1] + 3)/6;
  r[3] = (2*r[0] + 4*r[1] + 3)/6;
  g[3] = (2*g[0] + 4*g[1] + 3)/6;
  b[3] = (2*b[0] + 4*b[1] + 3)/6;

  for(int yo = 0; yo < 4; ++yo) {
    for(int xo = 0; xo < 4; ++xo) {
      rgba_rows[y+yo][4*x+4*xo+0] = r[ci & 3];
      rgba_rows[y+yo][4*x+4*xo+1] = g[ci & 3];
      rgba_rows[y+yo][4*x+4*xo+2] = b[ci & 3];
      ci >>= 2;
    }
  }
}

static void decode_dxt1(vtf_header_t* header, uint8_t *data, uint8_t** rgba_rows)
{
  int pos = 0;
  uint16_t c0, c1;
  uint32_t ci;

  for(int y = 0; y < header->height; y += 4) {
    for(int x = 0; x < header->width; x += 4) {
      c0 = data[pos++];
      c0 |= data[pos++] << 8;
      c1 = data[pos++];
      c1 |= data[pos++] << 8;
      ci = 0;
      for(int i = 0; i <= 24; i += 8)
        ci |= (uint64_t)data[pos++] << i;
      decode_dxt_colors(x, y, c0, c1, ci, rgba_rows);
      for(int yo = 0; yo < 4; ++yo)
        for(int xo = 0; xo < 4; ++xo)
          rgba_rows[y+yo][4*x+4*xo+3] = 255;
    }
  }
}

static void decode_dxt3(vtf_header_t* header, uint8_t *data, uint8_t** rgba_rows)
{
  int pos = 0;
  uint16_t c0, c1;
  uint32_t ci;
  uint64_t al;

  for(int y = 0; y < header->height; y += 4) {
    for(int x = 0; x < header->width; x += 4) {
      al = 0;
      for(int i = 0; i <= 48; i += 8)
        al |= (uint64_t)data[pos++] << i;
      c0 = data[pos++];
      c0 |= data[pos++] << 8;
      c1 = data[pos++];
      c1 |= data[pos++] << 8;
      ci = 0;
      for(int i = 0; i <= 24; i += 8)
        ci |= (uint64_t)data[pos++] << i;
      decode_dxt_colors(x, y, c0, c1, ci, rgba_rows);
      for(int yo = 0; yo < 4; ++yo) {
        for(int xo = 0; xo < 4; ++xo) {
          rgba_rows[y+yo][4*x+4*xo+3] = al & 15;
          al >>= 4;
        }
      }
    }
  }
}

static void decode_dxt5(vtf_header_t* header, uint8_t *data, uint8_t** rgba_rows)
{
  int pos = 0;
  uint8_t a[8];
  uint64_t ai;
  uint16_t c0, c1;
  uint32_t ci;

  for(int y = 0; y < header->height; y += 4) {
    for(int x = 0; x < header->width; x += 4) {
      a[0] = data[pos++];
      a[1] = data[pos++];
      if(a[0] > a[1]) {
        for(int i = 0; i < 6; ++i)
          a[2+i] = ((15-2*i)*a[0] + (1+i)*a[1] + 7) / 14;
      } else {
        for(int i = 0; i < 4; ++i)
          a[2+i] = ((5-i)*a[0] + (1+i)*a[1] + 2) / 5;
        a[6] = 0; a[7] = 255;
      }
      ai = 0;
      for(int i = 0; i <= 40; i += 8)
        ai |= (uint64_t)data[pos++] << i;
      for(int yo = 0; yo < 4; ++yo) {
        for(int xo = 0; xo < 4; ++xo) {
          rgba_rows[y+yo][4*x+4*xo+3] = a[ai & 7];
          ai >>= 3;
        }
      }
      c0 = data[pos++];
      c0 |= data[pos++] << 8;
      c1 = data[pos++];
      c1 |= data[pos++] << 8;
      ci = 0;
      for(int i = 0; i <= 24; i += 8)
        ci |= (uint64_t)data[pos++] << i;
      decode_dxt_colors(x, y, c0, c1, ci, rgba_rows);
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
        case IMAGE_FORMAT_RGB888:   r = data[pos++]; g = data[pos++]; b = data[pos++]; a = 255; break;
        case IMAGE_FORMAT_BGR888:   b = data[pos++]; g = data[pos++]; r = data[pos++]; a = 255; break;
      }
      rgba_rows[y][4*x+0] = r;
      rgba_rows[y][4*x+1] = g;
      rgba_rows[y][4*x+2] = b;
      rgba_rows[y][4*x+3] = a;
    }
  }
}

JNIEXPORT jboolean JNICALL Java_zzh_bin_valvevtftool_VtfLib_vtfToPng(JNIEnv* env, jobject obj, jstring vtfPath, jstring pngPath) {
    LOGI("vtfToPng: started");
    const char* vPath = get_string(env, vtfPath);
    const char* pPath = get_string(env, pngPath);
    LOGI("vtfToPng: paths: %s -> %s", vPath, pPath);
    int fd = open(vPath, O_RDONLY);
    if (fd < 0) { 
        LOGI("vtfToPng: failed to open file");
        release_string(env, vtfPath, vPath); release_string(env, pngPath, pPath); return JNI_FALSE; 
    }
    
    struct stat st; fstat(fd, &st); size_t filesize = st.st_size;
    LOGI("vtfToPng: file size: %zu", filesize);
    uint8_t* filedata = mmap(0, filesize, PROT_READ, MAP_PRIVATE, fd, 0);
    if (filedata == MAP_FAILED) {
        LOGI("vtfToPng: Failed to map file");
        close(fd);
        release_string(env, vtfPath, vPath); release_string(env, pngPath, pPath);
        return JNI_FALSE;
    }
    
    vtf_header_t* header = (vtf_header_t*)filedata;
    if (strncmp(header->signature, "VTF", 3) != 0) {
        LOGI("vtfToPng: Invalid VTF signature");
        munmap(filedata, filesize); close(fd);
        release_string(env, vtfPath, vPath); release_string(env, pngPath, pPath);
        return JNI_FALSE;
    }
    LOGI("vtfToPng: Header signature valid");
    
    // --- IMPROVED BOUNDS-CHECKED RESOURCE SEARCHING ---
    int image_end = filesize;
    if(header->version[1] > 2) {
        // Validate resources array is within file
        if (sizeof(vtf_header_t) + header->numResources * sizeof(vtf_resurce_entry_t) > filesize) {
             LOGI("vtfToPng: Resources overflow file size");
             munmap(filedata, filesize); close(fd);
             release_string(env, vtfPath, vPath); release_string(env, pngPath, pPath);
             return JNI_FALSE;
        }

        enum { FOUND_NOTHING, FOUND_IMAGEDATA, FOUND_END } search_state = FOUND_NOTHING;
        vtf_resurce_entry_t *resources = (vtf_resurce_entry_t*)(filedata+sizeof(vtf_header_t));
        for(unsigned i=0; i<header->numResources; i++) {
            switch(search_state) {
                case FOUND_NOTHING:
                    if (resources[i].tag[0] == 0x30) search_state = FOUND_IMAGEDATA;
                    break;
                case FOUND_IMAGEDATA:
                    if (resources[i].tag[0] != 'C' && resources[i].offset < filesize) {
                        image_end = resources[i].offset;
                        search_state = FOUND_END;
                    }
                    break;
                case FOUND_END: break;
            }
            if (search_state == FOUND_END) break;
        }
        if (search_state == FOUND_IMAGEDATA) image_end = filesize; // Fallback
    }
    LOGI("vtfToPng: image_end: %d", image_end);

    // Calculate data size for selected format to validate pointer
    size_t data_size = 0;
    size_t framesize = (size_t)header->width * header->height;
    switch(header->image_format) {
        case IMAGE_FORMAT_RGBA8888: case IMAGE_FORMAT_ARGB8888: case IMAGE_FORMAT_ABGR8888: case IMAGE_FORMAT_BGRA8888: data_size = framesize * 4; break;
        case IMAGE_FORMAT_RGB888: case IMAGE_FORMAT_BGR888: data_size = framesize * 3; break;
        case IMAGE_FORMAT_DXT1: data_size = ((header->width+3)/4) * ((header->height+3)/4) * 8; break;
        case IMAGE_FORMAT_DXT3: case IMAGE_FORMAT_DXT5: data_size = ((header->width+3)/4) * ((header->height+3)/4) * 16; break;
        default: break;
    }
    
    if (data_size == 0 || (size_t)image_end < data_size) {
        LOGI("vtfToPng: Invalid image data size calculation");
        munmap(filedata, filesize); close(fd);
        release_string(env, vtfPath, vPath); release_string(env, pngPath, pPath);
        return JNI_FALSE;
    }
    // --- END IMPROVED LOGIC ---

    uint8_t** rgba_rows = malloc(sizeof(uint8_t*)*header->height);
    if (!rgba_rows) {
        LOGI("vtfToPng: Failed to allocate rgba_rows");
        munmap(filedata, filesize); close(fd);
        release_string(env, vtfPath, vPath); release_string(env, pngPath, pPath);
        return JNI_FALSE;
    }
    for(int i = 0; i < header->height; ++i) {
        rgba_rows[i] = malloc(header->width * 4);
        if (!rgba_rows[i]) {
            for(int j=0; j<i; ++j) free(rgba_rows[j]);
            free(rgba_rows);
            LOGI("vtfToPng: Failed to allocate row %d", i);
            munmap(filedata, filesize); close(fd);
            release_string(env, vtfPath, vPath); release_string(env, pngPath, pPath);
            return JNI_FALSE;
        }
        memset(rgba_rows[i], 0xFF, header->width * 4);
    }
    LOGI("vtfToPng: rgba_rows allocated");
    
    int frame_offset = 1; 
    
    switch(header->image_format) {
        case IMAGE_FORMAT_RGBA8888:
        case IMAGE_FORMAT_ARGB8888:
        case IMAGE_FORMAT_ABGR8888:
        case IMAGE_FORMAT_BGRA8888:
        case IMAGE_FORMAT_RGB888:
        case IMAGE_FORMAT_BGR888:
            LOGI("vtfToPng: decoding rgba");
            decode_rgba(header, filedata + image_end - (data_size * frame_offset), rgba_rows);
            break;
        case IMAGE_FORMAT_DXT1:
            LOGI("vtfToPng: decoding dxt1");
            decode_dxt1(header, filedata + image_end - (data_size * frame_offset), rgba_rows);
            break;
        case IMAGE_FORMAT_DXT3:
            LOGI("vtfToPng: decoding dxt3");
            decode_dxt3(header, filedata + image_end - (data_size * frame_offset), rgba_rows);
            break;
        case IMAGE_FORMAT_DXT5:
            LOGI("vtfToPng: decoding dxt5");
            decode_dxt5(header, filedata + image_end - (data_size * frame_offset), rgba_rows);
            break;
        default:
            LOGI("vtfToPng: Unsupported format: %d", header->image_format);
    }
    
    LOGI("vtfToPng: writing png");
    write_png(pPath, header->width, header->height, rgba_rows);
    
    for(int i = 0; i < header->height; ++i) free(rgba_rows[i]);
    free(rgba_rows); munmap(filedata, filesize); close(fd);
    release_string(env, vtfPath, vPath); release_string(env, pngPath, pPath);
    LOGI("vtfToPng: finished");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_zzh_bin_valvevtftool_VtfLib_pngToVtf(JNIEnv* env, jobject obj, jstring pngPath, jstring vtfPath) {
    const char* pPath = get_string(env, pngPath);
    const char* vPath = get_string(env, vtfPath);

    // 1. Read PNG using libpng simplified API
    png_image image;
    memset(&image, 0, (sizeof image));
    image.version = PNG_IMAGE_VERSION;
    if (png_image_begin_read_from_file(&image, pPath) == 0) {
        LOGI("Failed to read PNG: %s", image.message);
        release_string(env, pngPath, pPath); release_string(env, vtfPath, vPath);
        return JNI_FALSE;
    }
    image.format = PNG_FORMAT_RGBA;
    png_byte* buffer = malloc(PNG_IMAGE_SIZE(image));
    if (!buffer) {
        LOGI("Failed to allocate buffer");
        release_string(env, pngPath, pPath); release_string(env, vtfPath, vPath);
        return JNI_FALSE;
    }
    if (png_image_finish_read(&image, NULL, buffer, 0, NULL) == 0) {
        LOGI("Failed to finish reading PNG: %s", image.message);
        free(buffer);
        release_string(env, pngPath, pPath); release_string(env, vtfPath, vPath);
        return JNI_FALSE;
    }

    // 2. Prepare Header
    vtf_header_t header;
    memset(&header, 0, sizeof(header));
    memcpy(header.signature, "VTF\0", 4);
    header.version[0] = 7;
    header.version[1] = 4;
    header.header_size = sizeof(header);
    header.width = (unsigned short)image.width;
    header.height = (unsigned short)image.height;
    header.image_format = IMAGE_FORMAT_RGBA8888;
    header.mipmap_count = 1;
    header.numResources = 0;

    // 3. Write VTF
    FILE* f = fopen(vPath, "wb");
    if (!f) {
        free(buffer);
        release_string(env, pngPath, pPath); release_string(env, vtfPath, vPath);
        return JNI_FALSE;
    }
    fwrite(&header, sizeof(header), 1, f);
    fwrite(buffer, PNG_IMAGE_SIZE(image), 1, f);
    fclose(f);

    // 4. Cleanup
    free(buffer);
    release_string(env, pngPath, pPath); release_string(env, vtfPath, vPath);
    return JNI_TRUE;
}
JNIEXPORT jobject JNICALL Java_zzh_bin_valvevtftool_VtfLib_getVtfBitmap(JNIEnv* env, jobject obj, jstring vtfPath) { return NULL; }
