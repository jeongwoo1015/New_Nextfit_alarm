package com.example.respeclive

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.respeclive.ui.nexty.NextyMotion
import com.example.respeclive.ui.nexty.nextyKeyPoses
import com.example.respeclive.ui.nexty.renderNextyPose
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 넥스티 동작 확인용 그림 뽑기. 동작마다 단계 자세와 중간 자세를 PNG로 저장함 (앱 외부 파일 폴더의 nexty/).
 * 동작을 고르려면 instrumentation 인자 motions=CAT_COW,CHILD_POSE (없으면 전부).
 */
@RunWith(AndroidJUnit4::class)
class NextyPoseSnapshot {
    @Test
    fun saveKeyPoses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val wanted = InstrumentationRegistry.getArguments().getString("motions")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "nexty").apply {
            deleteRecursively()
            mkdirs()
        }
        NextyMotion.entries.filter { wanted == null || it.name in wanted }.forEach { motion ->
            nextyKeyPoses(motion).forEachIndexed { i, pose ->
                val bitmap = renderNextyPose(motion, pose, 520, 480).asAndroidBitmap()
                File(dir, "%s_%03d.png".format(motion.name, i)).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
