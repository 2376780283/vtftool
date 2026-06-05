package zzh.bin.valvevtftool

import android.graphics.Bitmap

object VtfLib {
    init {
        System.loadLibrary("valvevtftool")
    }

    const val FORMAT_RGBA8888 = 0
    const val FORMAT_DXT1 = 13
    const val FORMAT_DXT3 = 14
    const val FORMAT_DXT5 = 15

    external fun vtfToPng(vtfPath: String, pngPath: String): Boolean
    external fun pngToVtf(pngPath: String, vtfPath: String, format: Int): Boolean
    external fun getVtfBitmap(vtfPath: String): Bitmap?
}
