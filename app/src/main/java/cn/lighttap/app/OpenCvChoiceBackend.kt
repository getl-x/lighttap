package cn.lighttap.app

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/** Uses the bundled official OpenCV library. No model, manager service or network is used. */
object OpenCvChoiceBackend {
    private var loaded = false
    fun classify(frame: ChoiceFrame, normal: UiBox, abnormal: UiBox): ChoiceResult {
        try {
            if (!loaded) {
                System.loadLibrary("opencv_java4")
                loaded = true
            }
            val rgba = ByteArray(frame.pixels.size*4)
            val masks = ByteArray(frame.pixels.size)
            val input = Mat(frame.height, frame.width, CvType.CV_8UC4)
            val selected = Mat(); val inactive = Mat(); val white = Mat(); val dark = Mat()
            try {
                for (i in frame.pixels.indices) {
                    val color = frame.pixels[i]
                    rgba[i*4] = (color shr 16 and 255).toByte(); rgba[i*4+1] = (color shr 8 and 255).toByte(); rgba[i*4+2] = (color and 255).toByte(); rgba[i*4+3] = 255.toByte()
                }
                input.put(0,0,rgba)
                Core.inRange(input, Scalar(13.0,125.0,238.0,0.0), Scalar(37.0,149.0,255.0,255.0), selected)
                Core.inRange(input, Scalar(213.0,213.0,213.0,0.0), Scalar(229.0,229.0,229.0,255.0), inactive)
                Core.inRange(input, Scalar(250.0,250.0,250.0,0.0), Scalar(255.0,255.0,255.0,255.0), white)
                Core.inRange(input, Scalar(0.0,0.0,0.0,0.0), Scalar(105.0,105.0,105.0,255.0), dark)
                val plane = ByteArray(frame.pixels.size)
                try {
                    fun merge(mat: Mat, bit: Int) { mat.get(0,0,plane); for(i in plane.indices) if(plane[i].toInt() != 0) masks[i] = (masks[i].toInt() or bit).toByte() }
                    merge(selected,1); merge(inactive,2); merge(white,4)
                    dark.get(0,0,plane)
                    for(i in plane.indices) if(plane[i].toInt() != 0) {
                        val color=frame.pixels[i]; val channels=intArrayOf(color shr 16 and 255, color shr 8 and 255,color and 255)
                        if(channels.max()-channels.min()<=35) masks[i]=(masks[i].toInt() or 8).toByte()
                    }
                    frame.classMask=masks
                    return ChoiceVision.classify(frame,normal,abnormal)
                } finally { plane.fill(0) }
            } finally {
                input.release(); selected.release(); inactive.release(); white.release(); dark.release()
                rgba.fill(0); masks.fill(0); frame.classMask=null
            }
        } catch (e: VisitMismatch) { throw e }
        catch (_: LinkageError) { throw VisitMismatch("本地视觉库与此设备不兼容，已暂停") }
        catch (_: Exception) { throw VisitMismatch("本地视觉校验失败，已暂停") }
    }
}