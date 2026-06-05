package zzh.bin.valvevtftool

import android.graphics.Bitmap

object VtfLib {
    init {
        System.loadLibrary("valvevtftool")
    }

    external fun vtfToPng(vtfPath: String, pngPath: String): Boolean
    external fun pngToVtf(pngPath: String, vtfPath: String, format: Int): Boolean
    external fun getVtfBitmap(vtfPath: String): Bitmap?
}
