package cn.lighttap.app

import org.junit.Assert.*
import org.junit.Test

class ChoiceVisionTest {
    private val normal = UiBox(4,4,91,47)
    private val abnormal = UiBox(90,4,177,47)
    private fun picture(normalOn: Boolean = true, abnormalOn: Boolean = false): ChoiceFrame {
        val w=182; val h=52; val data=IntArray(w*h) { 0xffffff }
        fun draw(box: UiBox, chosen: Boolean) {
            val color=if(chosen) 0x1989fa else 0xdddddd
            for(x in box.left until box.right){data[box.top*w+x]=color;data[(box.bottom-1)*w+x]=color}
            for(y in box.top until box.bottom){data[y*w+box.left]=color;data[y*w+box.right-1]=color}
            for(y in box.top+14 until box.top+28) for(x in box.left+30 until box.left+42) data[y*w+x]=if(chosen) 0x1989fa else 0x1d1f24
        }
        draw(normal,normalOn);draw(abnormal,abnormalOn)
        return ChoiceFrame(0,0,w,h,data)
    }
    @Test fun selectedNormalAndInactiveAbnormalMatch() { assertEquals(ChoiceResult.NORMAL,ChoiceVision.classify(picture(),normal,abnormal)) }
    @Test fun selectedAbnormalIsIdentified() { assertEquals(ChoiceResult.ABNORMAL,ChoiceVision.classify(picture(false,true),normal,abnormal)) }
    @Test fun bothSelectedAreUncertain() { assertEquals(ChoiceResult.UNKNOWN,ChoiceVision.classify(picture(true,true),normal,abnormal)) }
    @Test fun neitherSelectedIsUncertain() { assertEquals(ChoiceResult.UNKNOWN,ChoiceVision.classify(picture(false,false),normal,abnormal)) }
    @Test fun overlayCoveringButtonsIsRejected() {
        val f=picture(); for(y in 10..38) for(x in 10..175) f.pixels[y*f.width+x]=0x202030
        assertEquals(ChoiceResult.UNKNOWN,ChoiceVision.classify(f,normal,abnormal))
    }
    @Test fun unknownThemeIsRejected() {
        val f=picture(); for(i in f.pixels.indices) if(f.pixels[i]==0x1989fa) f.pixels[i]=0xff0000
        assertEquals(ChoiceResult.UNKNOWN,ChoiceVision.classify(f,normal,abnormal))
    }
    @Test fun missingBlueBorderIsRejectedEvenWithBlueText() {
        val f=picture();for(x in normal.left until normal.right){f.pixels[normal.top*f.width+x]=0xffffff;f.pixels[(normal.bottom-1)*f.width+x]=0xffffff}
        assertEquals(ChoiceResult.UNKNOWN,ChoiceVision.classify(f,normal,abnormal))
    }
    @Test fun clippedAndReversedGeometryIsRejected() {
        assertEquals(ChoiceResult.UNKNOWN,ChoiceVision.classify(picture(),normal.copy(left=-1),abnormal))
        assertEquals(ChoiceResult.UNKNOWN,ChoiceVision.classify(picture(),abnormal,normal))
    }
    @Test fun erasedFrameContainsNoPixels() {val f=picture();f.classMask=ByteArray(f.pixels.size){1};f.erase();assertTrue(f.pixels.all{it==0});assertNull(f.classMask)}
    @Test fun nativeMasksAndPureClassificationAgreeOnKnownFrame() {
        val f=picture(); val pure=ChoiceVision.classify(f,normal,abnormal)
        f.classMask=ByteArray(f.pixels.size){ i -> when(f.pixels[i]){0x1989fa->1;0xdddddd->2;0xffffff->4;0x1d1f24->8;else->0}.toByte() }
        assertEquals(pure,ChoiceVision.classify(f,normal,abnormal))
    }
}