package com.example.respeclive.ui.nexty

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// 캐릭터 시안의 색
private val BODY = Color(0xFFFFC37A)
private val OUTLINE = Color(0xFF3A2E2A)
private val SUN_RAY = Color(0xFFFFD45C)
private val NIGHT_CAP = Color(0xFF4A5AA8)
private val BELLY = Color(0xFFFFDDB0)
private val BLUSH = Color(0xFFFF9E8F)
private val WATER = Color(0xFF9CD3F2)

/** 그리는 좌표계 (몸 중심 x = 100). 화면 크기에 맞춰 통째로 늘리거나 줄임. 좌우 여백은 팔을 뻗을 자리 */
private const val CANVAS_W = 260f
private const val CANVAS_H = 240f
private const val MARGIN_X = 30f
private const val OUTLINE_W = 4f
private const val ARM_W = 11f
private const val UPPER_ARM = 18f
private const val LOWER_ARM = 16f
private const val MAX_UPPER_REACH = 1.5f
private const val SHOULDER_X = 54f
private const val SHOULDER_Y = 128f
private const val CROSS_ARM_DROP = 28f      // 오답 X 팔을 어깨 높이에서 내린 정도 (눈이 보이도록)

/** 표정 (눈과 입만 바뀜) */
enum class NextyFace { NORMAL, HAPPY, GRIN, SLEEPY, FOCUS, WINCE }

/**
 * 동작에 함께 그리는 소품·효과.
 * RAISED_HAND·WRIST_CIRCLE: 오른팔 끝에 손 모양 (주먹·편 손 / 돌아가는 손목).
 * CUE_*: 움직이는 방향을 알려 주는 화살표. CROSS_PULL·WRIST_UP·WRIST_DOWN: 팔을 자세 대신 따로 그림 (act로 진행).
 * CALF_WALL: 벽과 뒤로 뺀 다리 (act로 진행)
 */
enum class NextyProp {
    NONE, CUP, MEMO, SUN, STRETCH_LINES, SPARKLES, WELCOME_SPARKLES, ARM_CIRCLE, ARM_CROSS,
    RAISED_HAND, WRIST_CIRCLE, CUE_CHIN, CUE_HEAD, CUE_PELVIS, CROSS_PULL, WRIST_UP, WRIST_DOWN, CALF_WALL
}

/**
 * 몸 자세. 누운 자세(LIE, SIDE)는 선 자세 그대로 그린 뒤 머리가 왼쪽으로 가게 통째로 눕힘.
 * 눕히면 몸의 오른쪽(오른팔 쪽)이 화면 위쪽, 아래쪽(발 쪽)이 화면 오른쪽이 됨.
 * LIE는 천장에서 비스듬히 내려다본 모습: 몸과 두 다리가 화면을 향하고, 천장 쪽으로 든 무릎·다리·팔은 화면 위쪽으로 보임.
 * SIDE는 화면 쪽을 보고 옆으로 누운 모습을 앞 위에서 본 것: 화면 쪽으로 굽힌 무릎은 아래쪽, 몸 뒤쪽은 위쪽으로 보임
 */
enum class NextyPosture { STAND, SIT, LIE, SIDE }

/** 누운 자세에서 다리를 매트 위에 쭉 뻗은 각도 */
private const val LEG_STRAIGHT = 0f

/**
 * 한 순간의 자세. 팔 각도는 아래로 내린 상태가 0, 바깥으로 들수록 커지고 위로 곧게 올리면 180.
 * 팔꿈치 각도는 위팔에 더하는 값 (양수: 위쪽으로 굽힘, 음수: 몸 쪽으로 굽힘).
 * reach는 팔 길이 배율: 평소(1)에는 짧고, 팔을 쓰는 동작에서 늘어남 (위팔은 MAX_UPPER_REACH까지).
 * 다리는 누운 자세에서만 그림 (near: 누우면 왼다리 = 화면 쪽에 가까운 다리, 옆으로 누우면 위쪽 다리 / far: 다른 다리).
 * 허벅지 각도는 발 쪽으로 뻗은 상태가 0이고 천장(옆으로 누우면 화면) 쪽으로 들수록 커짐,
 * 무릎은 허벅지에 더하는 값(음수: 매트 쪽으로 접음), 발목은 양수일수록 발끝을 밂. hip은 엉덩이를 드는 정도(브릿지).
 * legLean은 세운 무릎을 옆으로 넘긴 각도 (+: 몸 오른쪽). faceX·faceY는 얼굴을 옮긴 정도 (고개 돌리기·턱 당기기).
 * grip은 주먹 쥔 정도, wristTurn은 손목을 돌린 각도, act는 따로 그리는 동작의 진행도 (0: 쉬는 자세 ~ 1, 음수면 반대쪽).
 * forearmsBehind면 팔꿈치 아래를 몸 앞에 다시 그리지 않음 (머리 뒤·등 뒤로 넘긴 손)
 */
data class NextyPose(
    val tilt: Float = 0f,          // 몸 기울기 (시계 방향 +)
    val stretch: Float = 1f,       // 위아래로 늘어나는 정도
    val leftShoulder: Float = 18f,
    val leftElbow: Float = -5f,
    val rightShoulder: Float = 18f,
    val rightElbow: Float = -5f,
    val leftReach: Float = 1f,
    val rightReach: Float = 1f,
    val nearThigh: Float = LEG_STRAIGHT,
    val nearKnee: Float = 0f,
    val nearAnkle: Float = 0f,
    val farThigh: Float = LEG_STRAIGHT,
    val farKnee: Float = 0f,
    val farAnkle: Float = 0f,
    val hip: Float = 0f,
    val legLean: Float = 0f,
    val faceX: Float = 0f,
    val faceY: Float = 0f,
    val grip: Float = 0f,
    val wristTurn: Float = 0f,
    val act: Float = 0f,
    val forearmsBehind: Boolean = false
) {
    /** 좌우를 바꾼 자세 (팔과 다리를 서로 바꿈) */
    fun mirrored() = swappedLegs().copy(
        tilt = -tilt, legLean = -legLean, faceX = -faceX, act = -act,
        leftShoulder = rightShoulder, leftElbow = rightElbow, leftReach = rightReach,
        rightShoulder = leftShoulder, rightElbow = leftElbow, rightReach = leftReach
    )

    /** 두 다리만 바꾼 자세 (누워서 한쪽 다리씩 하는 동작의 반대쪽) */
    fun swappedLegs() = copy(
        nearThigh = farThigh, nearKnee = farKnee, nearAnkle = farAnkle,
        farThigh = nearThigh, farKnee = nearKnee, farAnkle = nearAnkle
    )
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun lerp(a: NextyPose, b: NextyPose, t: Float) = NextyPose(
    tilt = lerp(a.tilt, b.tilt, t),
    stretch = lerp(a.stretch, b.stretch, t),
    leftShoulder = lerp(a.leftShoulder, b.leftShoulder, t),
    leftElbow = lerp(a.leftElbow, b.leftElbow, t),
    rightShoulder = lerp(a.rightShoulder, b.rightShoulder, t),
    rightElbow = lerp(a.rightElbow, b.rightElbow, t),
    leftReach = lerp(a.leftReach, b.leftReach, t),
    rightReach = lerp(a.rightReach, b.rightReach, t),
    nearThigh = lerp(a.nearThigh, b.nearThigh, t),
    nearKnee = lerp(a.nearKnee, b.nearKnee, t),
    nearAnkle = lerp(a.nearAnkle, b.nearAnkle, t),
    farThigh = lerp(a.farThigh, b.farThigh, t),
    farKnee = lerp(a.farKnee, b.farKnee, t),
    farAnkle = lerp(a.farAnkle, b.farAnkle, t),
    hip = lerp(a.hip, b.hip, t),
    legLean = lerp(a.legLean, b.legLean, t),
    faceX = lerp(a.faceX, b.faceX, t),
    faceY = lerp(a.faceY, b.faceY, t),
    grip = lerp(a.grip, b.grip, t),
    wristTurn = lerp(a.wristTurn, b.wristTurn, t),
    act = lerp(a.act, b.act, t),
    forearmsBehind = if (t < 0.5f) a.forearmsBehind else b.forearmsBehind
)

/** 자세로 이동하는 데 걸리는 시간. 같은 자세를 다시 넣으면 그만큼 유지됨 */
data class NextyStep(val pose: NextyPose, val ms: Int)

/** 반복되는 동작. posture는 몸 자세(서서·양반다리·누워서·옆으로 누워서), face는 이 동작의 기본 표정 */
enum class NextyMotion(
    val label: String,
    val posture: NextyPosture,
    val face: NextyFace,
    val prop: NextyProp,
    val steps: List<NextyStep>
) {
    IDLE("기본", NextyPosture.STAND, NextyFace.NORMAL, NextyProp.NONE, listOf(NextyStep(NextyPose(), 1000))),
    WELCOME(
        "반기기", NextyPosture.STAND, NextyFace.GRIN, NextyProp.WELCOME_SPARKLES,
        listOf(
            NextyStep(welcomePose(158f), 700),
            NextyStep(welcomePose(148f), 700)
        )
    ),
    WAVE(
        "손 흔들기", NextyPosture.STAND, NextyFace.GRIN, NextyProp.NONE,
        listOf(
            NextyStep(wavePose(150f, 18f), 450),
            NextyStep(wavePose(128f, 36f), 450)
        )
    ),
    // 퀴즈 정답: 두 팔을 둥글게 들어 머리 위에서 맞닿는 동그라미(O). 팔은 자세 대신 곡선으로 그림 (ARM_CIRCLE)
    QUIZ_O(
        "정답 O", NextyPosture.STAND, NextyFace.HAPPY, NextyProp.ARM_CIRCLE,
        listOf(
            NextyStep(NextyPose(), 450),
            NextyStep(NextyPose(stretch = 1.04f), 550)
        )
    ),
    // 퀴즈 오답: 배 앞에서 두 팔뚝을 대각선으로 엇갈려 X. 팔은 자세 대신 따로 그림 (ARM_CROSS)
    QUIZ_X(
        "오답 X", NextyPosture.STAND, NextyFace.WINCE, NextyProp.ARM_CROSS,
        listOf(
            NextyStep(NextyPose(tilt = -3f), 450),
            NextyStep(NextyPose(tilt = 3f), 450)
        )
    ),
    CHEER(
        "칭찬", NextyPosture.STAND, NextyFace.HAPPY, NextyProp.SPARKLES,
        listOf(
            NextyStep(wavePose(150f, 18f), 350),
            NextyStep(wavePose(128f, 36f), 350)
        )
    ),
    WATER(
        "물 한 컵 마시기", NextyPosture.STAND, NextyFace.NORMAL, NextyProp.CUP,
        listOf(
            NextyStep(cupLowPose(), 700), NextyStep(cupLowPose(), 700),
            NextyStep(cupHighPose(), 800), NextyStep(cupHighPose(), 1200)
        )
    ),
    STRETCH(
        "가벼운 스트레칭", NextyPosture.STAND, NextyFace.HAPPY, NextyProp.STRETCH_LINES,
        listOf(
            NextyStep(armsUpPose(), 900),
            NextyStep(armsUpPose().copy(stretch = 1.06f), 800),
            NextyStep(armsUpPose().copy(stretch = 1.06f), 1300),
            NextyStep(armsUpPose(), 800)
        )
    ),
    SUNLIGHT(
        "햇빛 보기", NextyPosture.STAND, NextyFace.NORMAL, NextyProp.SUN,
        listOf(
            NextyStep(sunPose(-3f), 1000), NextyStep(sunPose(-3f), 1000),
            NextyStep(sunPose(-8f), 900), NextyStep(sunPose(-8f), 1000)
        )
    ),
    TODO(
        "오늘 할 일 정하기", NextyPosture.STAND, NextyFace.NORMAL, NextyProp.MEMO,
        listOf(
            NextyStep(memoPose(0f), 800), NextyStep(memoPose(0f), 900),
            NextyStep(memoPose(4f), 500), NextyStep(memoPose(-4f), 500)
        )
    ),
    // ---- 아침 스트레칭: ai/tasks/stretch_sets.py의 동작 23개와 이름(label)이 같음. 안내 문장의 순서·머무는 시간을 따라 움직임 ----
    // 누워서
    LIE_REACH(
        "누워서 기지개", NextyPosture.LIE, NextyFace.HAPPY, NextyProp.NONE,
        List(3) {
            listOf(
                NextyStep(lieReachPose(), 1000),
                NextyStep(lieReachPose().copy(stretch = 1.06f, nearAnkle = 30f, farAnkle = 30f), 800),
                NextyStep(lieReachPose().copy(stretch = 1.06f, nearAnkle = 30f, farAnkle = 30f), 1800),
                NextyStep(relaxedPose(), 1000)
            )
        }.flatten()
    ),
    KNEE_ROCK(
        "무릎 좌우로 넘기기", NextyPosture.LIE, NextyFace.FOCUS, NextyProp.NONE,
        listOf(
            NextyStep(relaxedPose().kneesBent(), 900),
            NextyStep(relaxedPose().kneesFallen(1f), 1300),
            NextyStep(relaxedPose().kneesBent(), 1100),
            NextyStep(relaxedPose().kneesFallen(-1f), 1300),
            NextyStep(relaxedPose().kneesBent(), 1100)
        )
    ),
    KNEE_HUG(
        "무릎 가슴으로 당기기", NextyPosture.LIE, NextyFace.FOCUS, NextyProp.NONE,
        holdLegs(kneeHugPose(), reachMs = 1200, holdMs = 5000)
    ),
    // 얼굴이 가슴 쪽으로 내려가며 몸이 살짝 길어짐 (뒷목을 늘임). 화살표로 당기는 방향 표시
    CHIN_TUCK(
        "턱 당기기", NextyPosture.LIE, NextyFace.FOCUS, NextyProp.CUE_CHIN,
        List(5) {
            listOf(
                NextyStep(relaxedPose().copy(faceY = 9f, stretch = 1.03f), 700),
                NextyStep(relaxedPose().copy(faceY = 9f, stretch = 1.03f), 1800),
                NextyStep(relaxedPose(), 700)
            )
        }.flatten()
    ),
    // 얼굴이 한쪽으로 돌아감 (눈·입이 옆으로 옮겨 감). 화살표로 좌우 방향 표시
    HEAD_TURN(
        "고개 좌우로 돌리기", NextyPosture.LIE, NextyFace.FOCUS, NextyProp.CUE_HEAD,
        holdBothSides(relaxedPose().copy(faceX = 16f), reachMs = 1000, holdMs = 3500)
    ),
    // 천장으로 뻗은 손을 활짝 폈다가 꽉 쥠
    FIST(
        "주먹 쥐었다 펴기", NextyPosture.LIE, NextyFace.HAPPY, NextyProp.RAISED_HAND,
        listOf(NextyStep(ceilingArmPose(), 900)) + List(5) {
            listOf(NextyStep(ceilingArmPose().copy(grip = 1f), 450), NextyStep(ceilingArmPose().copy(grip = 1f), 250),
                NextyStep(ceilingArmPose(), 450), NextyStep(ceilingArmPose(), 250))
        }.flatten()
    ),
    // 천장으로 뻗은 손이 손목을 축으로 바깥쪽으로 두 바퀴, 안쪽으로 두 바퀴 돎
    WRIST_ROLL(
        "손목 돌리기", NextyPosture.LIE, NextyFace.FOCUS, NextyProp.WRIST_CIRCLE,
        listOf(NextyStep(ceilingArmPose(), 900)) +
            listOf(180f, 360f, 540f, 720f, 540f, 360f, 180f, 0f).map { NextyStep(ceilingArmPose().copy(wristTurn = it), 650) }
    ),
    // 무릎을 세운 채 허리를 누르면 무릎이 머리 쪽으로, 띄우면 발 쪽으로 살짝 흔들림
    PELVIC_TILT(
        "골반 기울이기", NextyPosture.LIE, NextyFace.FOCUS, NextyProp.CUE_PELVIS,
        List(4) {
            listOf(
                NextyStep(relaxedPose().kneesBent().copy(nearThigh = 60f, farThigh = 60f, nearKnee = -124f, farKnee = -124f, stretch = 0.97f), 1100),
                NextyStep(relaxedPose().kneesBent().copy(nearThigh = 42f, farThigh = 42f, nearKnee = -108f, farKnee = -108f, hip = 4f), 1100)
            )
        }.flatten()
    ),
    KNEES_HUG(
        "두 무릎 가슴으로 당기기", NextyPosture.LIE, NextyFace.FOCUS, NextyProp.NONE,
        kneeHugPose().copy(farThigh = 140f, farKnee = -110f).let { hug ->
            listOf(
                NextyStep(hug, 1200), NextyStep(hug, 5000),
                NextyStep(relaxedPose().kneesBent(), 1000), NextyStep(relaxedPose().kneesBent(), 1200)
            )
        }
    ),
    BRIDGE(
        "브릿지", NextyPosture.LIE, NextyFace.FOCUS, NextyProp.NONE,
        relaxedPose().kneesBent().let { bent ->
            val up = bent.copy(hip = 12f, nearThigh = 22f, nearKnee = -100f, farThigh = 22f, farKnee = -100f)
            listOf(NextyStep(bent, 900), NextyStep(up, 1200), NextyStep(up, 2000), NextyStep(bent, 1300))
        }
    ),
    FIGURE_FOUR(
        "4자 스트레칭", NextyPosture.LIE, NextyFace.FOCUS, NextyProp.NONE,
        holdLegs(
            relaxedPose().copy(
                nearThigh = 130f, nearKnee = -60f, farThigh = 105f, farKnee = -120f, legLean = 20f,
                rightShoulder = -55f, rightElbow = 0f, rightReach = 3.4f
            ),
            reachMs = 1300, holdMs = 5000
        )
    ),
    LIE_TWIST(
        "누워서 비틀기", NextyPosture.LIE, NextyFace.FOCUS, NextyProp.NONE,
        // 두 팔을 옆으로 벌리고, 무릎을 넘긴 반대쪽으로 고개를 돌림
        relaxedPose().copy(
            rightShoulder = 128f, rightElbow = 0f, rightReach = 1.1f, leftShoulder = 128f, leftElbow = 0f, leftReach = 1.1f
        ).let { arms ->
            val right = arms.kneesFallen(1f).copy(faceX = -14f)
            val left = arms.kneesFallen(-1f).copy(faceX = 14f)
            listOf(
                NextyStep(arms.kneesBent(), 1000),
                NextyStep(right, 1300), NextyStep(right, 4000),
                NextyStep(arms.kneesBent(), 1000),
                NextyStep(left, 1300), NextyStep(left, 4000)
            )
        }
    ),
    ANKLE_PUMP(
        "발목 당겼다 밀기", NextyPosture.LIE, NextyFace.HAPPY, NextyProp.NONE,
        List(5) {
            listOf(
                NextyStep(relaxedPose().copy(nearAnkle = -25f, farAnkle = -25f), 600),
                NextyStep(relaxedPose().copy(nearAnkle = 45f, farAnkle = 45f), 600)
            )
        }.flatten()
    ),
    HAMSTRING(
        "허벅지 뒤 늘이기", NextyPosture.LIE, NextyFace.FOCUS, NextyProp.NONE,
        relaxedPose().kneesBent().copy(rightShoulder = -50f, rightElbow = 0f, rightReach = 3.6f).let { base ->
            val bent = base.copy(nearThigh = 100f, nearKnee = -70f)
            val straight = base.copy(nearThigh = 92f, nearKnee = -8f)
            listOf(
                NextyStep(bent, 1200), NextyStep(straight, 1500), NextyStep(straight, 4000), NextyStep(base, 1000),
                NextyStep(bent.swappedLegs(), 1200), NextyStep(straight.swappedLegs(), 1500),
                NextyStep(straight.swappedLegs(), 4000), NextyStep(base, 1000)
            )
        }
    ),
    // 옆으로 누워서: 무릎을 접고 옆으로 누움
    SIDE_CHEST_OPEN(
        "팔 열어 가슴 펴기", NextyPosture.SIDE, NextyFace.HAPPY, NextyProp.NONE,
        sideLyingPose().let { side ->
            val front = side.copy(rightShoulder = 60f, rightElbow = 0f, rightReach = 2.4f)
            List(3) {
                listOf(
                    NextyStep(front, 900),
                    NextyStep(front.copy(rightShoulder = 120f), 1100),
                    NextyStep(front.copy(rightShoulder = 190f, stretch = 1.04f), 1100),
                    NextyStep(front.copy(rightShoulder = 190f, stretch = 1.04f), 1500)
                )
            }.flatten()
        }
    ),
    // 아래쪽 다리는 굽힌 채, 위쪽 다리 무릎을 접어 발뒤꿈치를 엉덩이 뒤로 당기고 위쪽 손이 등 뒤에서 발목을 잡음
    SIDE_QUAD(
        "허벅지 앞 늘이기", NextyPosture.SIDE, NextyFace.FOCUS, NextyProp.NONE,
        relaxedPose().copy(farThigh = 60f, farKnee = -90f, nearThigh = 20f, nearKnee = -30f).let { base ->
            val pull = base.copy(
                nearThigh = -20f, nearKnee = -145f,
                rightShoulder = 25f, rightElbow = -56f, rightReach = 2.2f, forearmsBehind = true
            )
            listOf(NextyStep(base, 900), NextyStep(pull, 1300), NextyStep(pull, 5000), NextyStep(base, 1200))
        }
    ),
    // 앉아서 (양반다리)
    NECK_TILT(
        "목 옆으로 기울이기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.NONE,
        holdBothSides(relaxedPose().copy(tilt = 13f), reachMs = 1000, holdMs = 5000)
    ),
    // 오른팔을 가슴 앞으로 곧게 가로지르고 왼팔로 팔꿈치를 걸어 당김. 고개는 반대쪽으로 살짝
    CROSS_PULL(
        "어깨 교차 당기기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.CROSS_PULL,
        holdBothSides(relaxedPose().copy(act = 1f, faceX = 6f), reachMs = 900, holdMs = 5000) + deepBreaths()
    ),
    // 오른팔을 앞으로 뻗어 손바닥이 보이게 손끝을 세우고, 왼손으로 손가락을 잡아 당김
    WRIST_FRONT(
        "손목 앞쪽 늘이기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.WRIST_UP,
        holdBothSides(relaxedPose().copy(act = 1f), reachMs = 900, holdMs = 5000)
    ),
    // 오른팔을 앞으로 뻗어 손등이 보이게 손끝을 내리고, 왼손으로 손등을 눌러 줌
    WRIST_BACK(
        "손목 뒤쪽 늘이기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.WRIST_DOWN,
        holdBothSides(relaxedPose().copy(act = 1f), reachMs = 900, holdMs = 5000)
    ),
    ELBOW_BEHIND(
        "팔꿈치 머리 뒤로 당기기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.NONE,
        holdBothSides(elbowBehindPose(), reachMs = 1100, holdMs = 5000) + deepBreaths()
    ),
    // 서서
    OVERHEAD(
        "만세 기지개", NextyPosture.STAND, NextyFace.HAPPY, NextyProp.STRETCH_LINES,
        List(2) {
            listOf(
                NextyStep(armsUpPose(), 1000),
                NextyStep(armsUpPose().copy(stretch = 1.07f), 800),
                NextyStep(armsUpPose().copy(stretch = 1.07f), 4500),
                NextyStep(relaxedPose(), 1000)
            )
        }.flatten() + deepBreaths()
    ),
    // 벽을 짚고 몸을 기울인 채 한 발을 뒤로 빼 뒤꿈치를 바닥에 붙임
    CALF(
        "종아리 늘이기", NextyPosture.STAND, NextyFace.FOCUS, NextyProp.CALF_WALL,
        holdBothSides(wallPose(), reachMs = 1000, holdMs = 5000) + deepBreaths()
    )
}

/** 한쪽 자세로 가서 머물고 돌아온 뒤 반대쪽도 같은 방법으로 (좌우 번갈아 하는 스트레칭) */
private fun holdBothSides(peak: NextyPose, reachMs: Int, holdMs: Int) = listOf(
    NextyStep(peak, reachMs), NextyStep(peak, holdMs), NextyStep(relaxedPose(), 900),
    NextyStep(peak.mirrored(), reachMs), NextyStep(peak.mirrored(), holdMs), NextyStep(relaxedPose(), 900)
)

/** 누워서 한쪽 다리로 머물고 돌아온 뒤 다른 다리도 (팔은 그대로) */
private fun holdLegs(peak: NextyPose, reachMs: Int, holdMs: Int) = listOf(
    NextyStep(peak, reachMs), NextyStep(peak, holdMs), NextyStep(relaxedPose(), 1000),
    NextyStep(peak.swappedLegs(), reachMs), NextyStep(peak.swappedLegs(), holdMs), NextyStep(relaxedPose(), 1000)
)

/** 마지막 깊은 숨 3번: 숨을 들이쉬며 몸이 살짝 늘어났다가 내쉼 */
private fun deepBreaths() = List(3) {
    listOf(NextyStep(relaxedPose().copy(stretch = 1.04f), 1300), NextyStep(relaxedPose(), 1300))
}.flatten()

// 두 팔을 머리 양옆으로 들어 반기기
private fun welcomePose(shoulder: Float) = NextyPose(
    leftShoulder = shoulder, leftElbow = 20f, rightShoulder = shoulder, rightElbow = 20f, leftReach = 3f, rightReach = 3f
)

// 한 팔을 들어 흔들기
private fun wavePose(shoulder: Float, elbow: Float) = NextyPose(rightShoulder = shoulder, rightElbow = elbow, rightReach = 1.9f)

// 두 손으로 컵을 배 앞에 / 입 가까이
private fun cupLowPose() = NextyPose(
    leftShoulder = 25f, leftElbow = -115f, rightShoulder = 25f, rightElbow = -115f, leftReach = 3f, rightReach = 3f
)
private fun cupHighPose() = NextyPose(
    leftShoulder = 60f, leftElbow = -150f, rightShoulder = 60f, rightElbow = -150f, leftReach = 3f, rightReach = 3f
)

// 두 팔을 머리 위로 모으기
private fun armsUpPose() = NextyPose(
    leftShoulder = 165f, leftElbow = 60f, rightShoulder = 165f, rightElbow = 60f, leftReach = 4.4f, rightReach = 4.4f
)

// 손으로 그늘을 만들고 해 쪽을 바라봄 (해는 왼쪽 위)
private fun sunPose(tilt: Float) = NextyPose(tilt = tilt, rightShoulder = 150f, rightElbow = 80f, rightReach = 2.4f)

// 두 손으로 메모를 들고 있음
private fun memoPose(tilt: Float) = NextyPose(
    tilt = tilt, leftShoulder = 28f, leftElbow = -118f, rightShoulder = 28f, rightElbow = -118f, leftReach = 2.7f, rightReach = 2.7f
)

// 팔에 힘을 빼고 몸 옆에 내림 (스트레칭 사이의 기본 자세). 누운 자세에서는 팔이 몸 옆에, 다리는 쭉 뻗음
private fun relaxedPose() = NextyPose(leftShoulder = 10f, leftElbow = 0f, rightShoulder = 10f, rightElbow = 0f, leftReach = 1.3f, rightReach = 1.3f)

// 누운 채 무릎을 세워 발바닥을 매트에 둠
private fun NextyPose.kneesBent() = copy(nearThigh = 50f, nearKnee = -116f, farThigh = 50f, farKnee = -116f, legLean = 0f)

// 세운 무릎을 모은 채 옆으로 넘김 (dir 1: 몸 오른쪽 = 화면 위쪽, -1: 왼쪽 = 화면 아래쪽)
private fun NextyPose.kneesFallen(dir: Float) = kneesBent().copy(legLean = 58f * dir)

// 누운 채 두 팔을 머리 위로 뻗음
private fun lieReachPose() = relaxedPose().copy(
    leftShoulder = 175f, rightShoulder = 175f, leftReach = 2.6f, rightReach = 2.6f
)

// 누운 채 한 팔을 천장으로 뻗음 (뒤쪽 팔은 앞쪽 팔에 가려지므로 몸 옆에 둠)
private fun ceilingArmPose() = relaxedPose().copy(rightShoulder = 90f, rightElbow = 0f, rightReach = 1.5f)

// 누운 채 왼쪽 무릎을 가슴으로 당겨 손으로 감쌈. 다른 다리는 매트 위에 쭉 뻗음
private fun kneeHugPose() = relaxedPose().copy(
    nearThigh = 140f, nearKnee = -110f,
    rightShoulder = -57f, rightElbow = 0f, rightReach = 2.0f, leftShoulder = -61f, leftElbow = 0f, leftReach = 2.45f
)

// 무릎을 접고 옆으로 누움
private fun sideLyingPose() = relaxedPose().copy(nearThigh = 60f, nearKnee = -90f, farThigh = 55f, farKnee = -85f)

// 오른팔을 들어 팔꿈치를 접고 손을 등 뒤로, 왼손은 머리 뒤에서 팔꿈치를 당김 (두 손 모두 머리 뒤)
private fun elbowBehindPose() = NextyPose(
    rightShoulder = 172f, rightElbow = 120f, rightReach = 2f,
    leftShoulder = 175f, leftElbow = 90f, leftReach = 3f,
    forearmsBehind = true
)

// 왼쪽 벽을 왼손으로 어깨 높이에서 짚고 몸을 벽 쪽으로 기울임. 다리는 CALF_WALL이 act만큼 뒤로 뺌
private fun wallPose() = relaxedPose().copy(tilt = -9f, leftShoulder = 84f, leftElbow = 6f, leftReach = 1.6f, act = 1f)

/**
 * 코드로 그리는 캐릭터 넥스티. 몸·팔·다리·표정을 나눠 그리고, 자세 사이를 부드럽게 이어 움직임.
 * 동작을 바꾸면 지금 자세에서 다음 동작으로 자연스럽게 넘어감. night면 햇살 꼭지 대신 달 수면모자.
 * speaking이면 입을 움직임.
 */
@Composable
fun NextyCharacter(
    motion: NextyMotion,
    modifier: Modifier = Modifier,
    face: NextyFace = motion.face,
    night: Boolean = false,
    speaking: Boolean = false
) {
    var from by remember { mutableStateOf(NextyPose()) }
    var to by remember { mutableStateOf(NextyPose()) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(motion) {
        while (true) {
            for (step in motion.steps) {
                from = lerp(from, to, progress.value)
                to = step.pose
                progress.snapTo(0f)
                progress.animateTo(1f, tween(step.ms, easing = FastOutSlowInEasing))
            }
        }
    }

    val infinite = rememberInfiniteTransition(label = "nexty")
    val breath by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath"
    )
    val eyeOpen by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 3800
                1f at 3560
                0f at 3660
                1f at 3800
            }
        ),
        label = "blink"
    )
    // 말하는 입: 벌리는 폭과 속도를 매번 무작위로 정하고 가끔 잠깐 다묾. 말을 멈추면 부드럽게 원래 입으로
    val mouth = remember { Animatable(0f) }
    LaunchedEffect(speaking) {
        if (!speaking) {
            mouth.animateTo(0f, tween(150))
            return@LaunchedEffect
        }
        while (true) {
            val target = if (Random.nextFloat() < 0.2f) 0f else 0.2f + Random.nextFloat() * 0.8f
            mouth.animateTo(target, tween(Random.nextInt(110, 260), easing = FastOutSlowInEasing))
            if (Random.nextFloat() < 0.15f) delay(Random.nextLong(80, 220))
        }
    }

    Canvas(modifier) {
        val s = min(size.width / CANVAS_W, size.height / CANVAS_H)
        translate((size.width - CANVAS_W * s) / 2f, (size.height - CANVAS_H * s) / 2f) {
            scale(s, s, Offset.Zero) {
                // 누운 자세는 매트가 가운데 오도록 옮김
                val lying = motion.posture == NextyPosture.LIE || motion.posture == NextyPosture.SIDE
                translate(MARGIN_X + if (lying) LIE_SHIFT_X else 0f, if (lying) LIE_SHIFT_Y else 0f) {
                    drawNexty(
                        pose = lerp(from, to, progress.value),
                        posture = motion.posture,
                        prop = motion.prop,
                        face = face,
                        night = night,
                        breath = breath,
                        eyeOpen = eyeOpen,
                        // 말하는 동안은 다물어도 말하는 입 모양을 유지 (0이면 표정의 기본 입)
                        mouthOpen = if (speaking) maxOf(mouth.value, 0.001f) else mouth.value
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawNexty(
    pose: NextyPose,
    posture: NextyPosture,
    prop: NextyProp,
    face: NextyFace,
    night: Boolean,
    breath: Float,
    eyeOpen: Float,
    mouthOpen: Float
) {
    val base = Offset(100f, 196f)      // 몸이 기울고 늘어나는 기준점 (엉덩이)
    val lying = posture == NextyPosture.LIE || posture == NextyPosture.SIDE

    when (posture) {
        NextyPosture.STAND -> if (prop == NextyProp.CALF_WALL) drawCalfWall(pose) else drawFeet()
        NextyPosture.SIT -> drawCrossedLegs()
        else -> drawMat()
    }
    drawBackgroundProp(prop, breath)

    // 누운 자세: 선 자세로 그린 것을 머리가 왼쪽으로 가게 눕힘. hip만큼 등 위쪽을 축으로 엉덩이를 듦
    rotate(if (lying) -90f else 0f, LIE_PIVOT) {
        rotate(-pose.hip, HIP_PIVOT) {
            rotate(pose.tilt, base) {
                if (lying) drawLyingLegs(pose, posture, front = false)
                scale(1f - breath * 0.012f, pose.stretch + breath * 0.016f, base) {
                    if (!night) drawSunRays()
                    // 팔은 몸 뒤에서 나오고, 몸 쪽으로 굽힌 팔(허리에 손, 머리에 손, 소품 들기)은 끝부분만 몸 앞으로 다시 그림.
                    // 동그라미 팔(ARM_CIRCLE)·X 팔(ARM_CROSS)·스트레칭 팔(PoseArms)은 두 마디 팔로는 모양이 안 나와 따로 그림
                    val customArms = customArmsOf(prop)
                    if (prop == NextyProp.ARM_CIRCLE) {
                        drawCircleArms()
                    } else if (isPoseArms(prop)) {
                        drawPoseArms(prop, pose.act, front = false)
                    } else if (!customArms) {
                        drawArmBehind(left = true, pose.leftShoulder, pose.leftElbow, pose.leftReach)
                        drawArmBehind(left = false, pose.rightShoulder, pose.rightElbow, pose.rightReach)
                    }
                    drawBody()
                    // 고개를 돌려 얼굴이 옆으로 옮겨 가도 몸 밖으로 나가지 않게 몸 윤곽 안에만 그림
                    clipPath(bodyPath()) { translate(pose.faceX, pose.faceY) { drawFace(face, eyeOpen, mouthOpen) } }
                    if (night) drawNightCap()
                    drawHeldProp(prop, pose)
                }
                // 가슴 쪽으로 당기거나 천장으로 든 다리는 몸 앞에 다시 그림 (그 위에 다리를 잡은 손이 오도록 팔보다 먼저)
                if (lying) drawLyingLegs(pose, posture, front = true)
                scale(1f - breath * 0.012f, pose.stretch + breath * 0.016f, base) {
                    if (prop == NextyProp.ARM_CROSS) {
                        drawCrossArms()
                    } else if (isPoseArms(prop)) {
                        drawPoseArms(prop, pose.act, front = true)
                    } else if (!customArmsOf(prop) && !pose.forearmsBehind) {
                        drawForearmInFront(left = true, pose.leftShoulder, pose.leftElbow, pose.leftReach)
                        drawForearmInFront(left = false, pose.rightShoulder, pose.rightElbow, pose.rightReach)
                    }
                    drawHand(prop, pose)
                    drawCue(prop, breath)
                }
            }
        }
    }
}

private fun customArmsOf(prop: NextyProp) = prop == NextyProp.ARM_CIRCLE || prop == NextyProp.ARM_CROSS || isPoseArms(prop)

private fun isPoseArms(prop: NextyProp) = prop == NextyProp.CROSS_PULL || prop == NextyProp.WRIST_UP || prop == NextyProp.WRIST_DOWN

// ---------------- 누운 자세 ----------------

private val MAT = Color(0xFFE6D5C0)
private val MAT_EDGE = Color(0xFFD9C4AA)
private val LIE_PIVOT = Offset(100f, 134f)     // 눕히는 축: 등(x 38)이 화면 y 196(매트 아래쪽)에 옴
private val HIP_PIVOT = Offset(60f, 100f)      // 브릿지에서 엉덩이를 들 때의 축 (등 위쪽)
// 다리 뿌리 (몸에 가려짐). 누우면 두 다리가 나란히 화면을 향함: 왼다리(near)가 화면 아래쪽, 오른다리가 위쪽
private val LIE_NEAR_HIP = Offset(85f, 184f)
private val LIE_FAR_HIP = Offset(115f, 184f)
// 옆으로 누우면 위쪽 다리(near)가 화면 위쪽, 아래쪽 다리가 매트 쪽
private val SIDE_NEAR_HIP = Offset(126f, 184f)
private val SIDE_FAR_HIP = Offset(78f, 186f)
/** 누운 자세: 천장 쪽 높이가 화면 위쪽으로 보이는 비율 (천장에서 비스듬히 내려다봄) */
private const val LIE_DEPTH = 0.8f
/** 누운 자세를 옮기는 정도: 매트(가운데 105, 135)가 캔버스 가운데(130, 120)에 오도록 (원 안 가운데 정렬) */
private const val LIE_SHIFT_X = -5f
private const val LIE_SHIFT_Y = -15f
/** 옆으로 누운 자세: 화면 쪽(몸 앞)이 화면 아래쪽, 몸 뒤쪽이 화면 위쪽으로 보이는 비율 (앞 위에서 내려다봄) */
private const val SIDE_DEPTH = -0.9f
private const val THIGH = 34f
private const val SHIN = 34f
private const val FOOT = 11f
private const val LEG_W = 14f

/** 누운 자세 아래의 매트 (위에서 내려다본 직사각형) */
private fun DrawScope.drawMat() {
    drawRoundRect(MAT, Offset(-24f, 64f), Size(258f, 142f), CornerRadius(14f))
    drawRoundRect(MAT_EDGE, Offset(-24f, 64f), Size(258f, 142f), CornerRadius(14f), style = Stroke(3f))
}

/** 누운 자세 다리의 네 점 (엉덩이 · 무릎 · 발목 · 발끝, 선 자세 좌표)과 몸 앞에 보이는지 */
private class LyingLeg(val points: List<Offset>, val inFront: Boolean)

/**
 * 다리를 몸 기준 3차원으로 놓고 화면에 비춤. 허벅지·정강이·발은 '발 쪽(아래) ↔ 천장(옆으로 누우면 화면) 쪽' 평면에서 굽히고,
 * lean만큼 그 평면을 몸 옆으로 넘김. 천장 쪽 높이는 depth 비율만큼 몸 옆 방향(선 자세 x)으로 보임.
 * 무릎이 들려 있고(천장 쪽) 몸과 겹치면(가슴 쪽으로 당김·천장으로 듦) 몸 앞에 그림
 */
private fun lyingLeg(hip: Offset, thigh: Float, knee: Float, ankle: Float, lean: Float, depth: Float): LyingLeg {
    val leanR = Math.toRadians(lean.toDouble())
    fun next(from: Offset, degrees: Float, length: Float): Pair<Offset, Float> {
        val r = Math.toRadians(degrees.toDouble())
        val along = cos(r).toFloat() * length
        val up = sin(r).toFloat() * length
        val side = up * sin(leanR).toFloat()
        val height = up * cos(leanR).toFloat()
        return Offset(from.x + side + height * depth, from.y + along) to height
    }
    val (k, kneeHeight) = next(hip, thigh, THIGH)
    val (a, _) = next(k, thigh + knee, SHIN)
    val (t, _) = next(a, thigh + knee + 90f - ankle, FOOT)
    return LyingLeg(listOf(hip, k, a, t), kneeHeight > 15f && k.y < 196f)
}

/**
 * 누운 자세의 두 다리. front=false면 모두 몸 뒤에, true면 몸 앞에 보이는 다리만 허벅지 중간부터 다시 그림.
 * 몸 색을 허벅지 조금 위까지 이어 칠해 다시 그린 곳에 선이 생기지 않게 함. 화면 쪽에 가까운 near 다리를 나중에 그림
 */
private fun DrawScope.drawLyingLegs(pose: NextyPose, posture: NextyPosture, front: Boolean) {
    val side = posture == NextyPosture.SIDE
    val depth = if (side) SIDE_DEPTH else LIE_DEPTH
    val lean = if (side) 0f else pose.legLean
    listOf(
        lyingLeg(if (side) SIDE_FAR_HIP else LIE_FAR_HIP, pose.farThigh, pose.farKnee, pose.farAnkle, lean, depth),
        lyingLeg(if (side) SIDE_NEAR_HIP else LIE_NEAR_HIP, pose.nearThigh, pose.nearKnee, pose.nearAnkle, lean, depth)
    ).forEach { leg ->
        val p = leg.points
        val inFront = leg.inFront
        if (!front) {
            drawLimb(p, p, LEG_W)
        } else if (inFront) {
            drawLimb(
                listOf(lerpOffset(p[0], p[1], 0.5f)) + p.drop(1),
                listOf(lerpOffset(p[0], p[1], 0.3f)) + p.drop(1),
                LEG_W
            )
        }
    }
}

/** 몸 윤곽: 타원에서 위아래만 더 넓게 퍼뜨려 약간 뭉툭하게. 값을 키울수록 뭉툭 (타원은 34) */
private fun bodyPath(): Path {
    val head = 38f
    val bottom = 45f
    return Path().apply {
        moveTo(100f, 52f)
        cubicTo(100f + head, 52f, 162f, 84f, 162f, 124f)
        cubicTo(162f, 164f, 100f + bottom, 196f, 100f, 196f)
        cubicTo(100f - bottom, 196f, 38f, 164f, 38f, 124f)
        cubicTo(38f, 84f, 100f - head, 52f, 100f, 52f)
        close()
    }
}

private fun DrawScope.drawBody() {
    val body = bodyPath()
    drawPath(body, BODY)
    drawOval(BELLY, Offset(68f, 140f), Size(64f, 46f))
    drawPath(body, OUTLINE, style = Stroke(OUTLINE_W))
}

private fun DrawScope.drawFace(face: NextyFace, eyeOpen: Float, mouthOpen: Float) {
    drawOval(BLUSH, Offset(56f, 121f), Size(21f, 12f))
    drawOval(BLUSH, Offset(123f, 121f), Size(21f, 12f))

    listOf(80f, 120f).forEach { x ->
        when (face) {
            NextyFace.NORMAL -> drawOpenEye(x, eyeOpen)
            NextyFace.HAPPY, NextyFace.GRIN -> drawArc(   // 웃는 눈 ∩
                OUTLINE, 200f, 140f, false, Offset(x - 7f, 103f), Size(14f, 12f),
                style = Stroke(OUTLINE_W, cap = StrokeCap.Round)
            )
            NextyFace.FOCUS, NextyFace.SLEEPY -> drawArc(   // 감은 눈 ∪
                OUTLINE, 20f, 140f, false, Offset(x - 7f, 102f), Size(14f, 10f),
                style = Stroke(OUTLINE_W, cap = StrokeCap.Round)
            )
            NextyFace.WINCE -> {   // 찡그린 눈 > <
                val inward = if (x < 100f) 1f else -1f
                val eye = Path().apply {
                    moveTo(x - 6f * inward, 103f)
                    lineTo(x + 5f * inward, 109f)
                    lineTo(x - 6f * inward, 115f)
                }
                drawPath(eye, OUTLINE, style = Stroke(OUTLINE_W, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
    if (face == NextyFace.WINCE) drawSweat()

    if (mouthOpen > 0f) {
        val h = 4f + mouthOpen * 5f
        drawOval(OUTLINE, Offset(93f, 123f), Size(14f, h))
    } else {
        when (face) {
            NextyFace.HAPPY -> drawArc(
                OUTLINE, 20f, 140f, false, Offset(85f, 108f), Size(30f, 24f),
                style = Stroke(OUTLINE_W, cap = StrokeCap.Round)
            )
            NextyFace.GRIN -> {   // 활짝 웃는 입: 아래로 둥근 반원과 혀
                drawArc(OUTLINE, 0f, 180f, true, Offset(84f, 112f), Size(32f, 26f))
                drawOval(BLUSH, Offset(93f, 130f), Size(14f, 7f))
            }
            NextyFace.SLEEPY -> drawOval(OUTLINE, Offset(96f, 125f), Size(8f, 6f))
            NextyFace.WINCE -> {   // 물결 입
                val wave = Path().apply {
                    moveTo(88f, 128f)
                    quadraticTo(91f, 124f, 94f, 128f)
                    quadraticTo(97f, 132f, 100f, 128f)
                    quadraticTo(103f, 124f, 106f, 128f)
                    quadraticTo(109f, 132f, 112f, 128f)
                }
                drawPath(wave, OUTLINE, style = Stroke(OUTLINE_W * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            else -> drawArc(
                OUTLINE, 25f, 130f, false, Offset(90f, 113f), Size(20f, 14f),
                style = Stroke(OUTLINE_W, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * 퀴즈 정답의 동그라미 팔: 몸 옆(어깨 아래, 몸에 가려짐)에서 바깥으로 둥글게 올라가 머리 위에서 두 손이 맞닿음.
 * 한 팔씩 윤곽선과 색을 다 그려, 나중에 그린 팔의 윤곽선이 맞닿은 두 손의 경계가 되게 함
 */
private fun DrawScope.drawCircleArms() {
    listOf(-1f, 1f).forEach { side ->
        val arm = Path().apply {
            moveTo(100f + side * 50f, 132f)
            cubicTo(100f + side * 94f, 100f, 100f + side * 72f, 20f, 100f + side * 9f, 22f)
        }
        drawPath(arm, OUTLINE, style = Stroke(ARM_W + OUTLINE_W * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(arm, BODY, style = Stroke(ARM_W, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * 퀴즈 오답의 X 팔: 위팔 없이 두 팔뚝만 몸 앞에서 엇갈림. 어깨 자리(SHOULDER_X)에서 조금 아래로 내려온 곳에서 시작해
 * 살짝 휘며 입 앞(약 y 139)에서 교차하고 반대쪽 눈 옆까지 올라감. 팔 윗선이 눈 아래 끝(y 117)보다 아래라 눈은 보임.
 * 왼팔 기준 좌표이고 오른팔은 좌우를 뒤집음. 오른팔을 나중에 그려 겹친 곳의 윤곽선으로 교차가 보이게 함
 */
private fun DrawScope.drawCrossArms() {
    listOf(1f, -1f).forEach { side ->
        fun x(v: Float) = 100f + side * (v - 100f)
        val arm = Path().apply {
            moveTo(x(100f - SHOULDER_X), SHOULDER_Y + CROSS_ARM_DROP)
            quadraticTo(x(82f), 132f + CROSS_ARM_DROP, x(134f), 88f + CROSS_ARM_DROP)
        }
        drawPath(arm, OUTLINE, style = Stroke(ARM_W + OUTLINE_W * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(arm, BODY, style = Stroke(ARM_W, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 머리 오른쪽 옆의 땀방울 (찡그린 표정) */
private fun DrawScope.drawSweat() {
    val drop = Path().apply {
        moveTo(166f, 80f)
        cubicTo(169f, 86f, 172f, 89f, 172f, 93f)
        cubicTo(172f, 97f, 169f, 99f, 166f, 99f)
        cubicTo(163f, 99f, 160f, 97f, 160f, 93f)
        cubicTo(160f, 89f, 163f, 86f, 166f, 80f)
        close()
    }
    drawPath(drop, WATER)
    drawPath(drop, OUTLINE, style = Stroke(OUTLINE_W * 0.7f, join = StrokeJoin.Round))
}

private fun DrawScope.drawOpenEye(x: Float, open: Float) {
    if (open < 0.2f) {
        drawLine(OUTLINE, Offset(x - 6f, 110f), Offset(x + 6f, 110f), OUTLINE_W, StrokeCap.Round)
        return
    }
    val h = 13f * open
    drawOval(OUTLINE, Offset(x - 5.5f, 110f - h / 2f), Size(11f, h))
    if (open > 0.6f) drawCircle(Color.White, 2.2f, Offset(x + 2f, 107f))
}

/** 머리 위 햇살 꼭지: 풀잎 모양 세 개가 서로 떨어져 있음 (x 간격, 기울기). 뿌리는 몸에 가려짐 */
private fun DrawScope.drawSunRays() {
    val root = Offset(100f, 60f)
    val leaf = Path().apply {
        moveTo(root.x, root.y)
        cubicTo(root.x - 10f, root.y - 7f, root.x - 8f, root.y - 25f, root.x, root.y - 25f)
        cubicTo(root.x + 8f, root.y - 25f, root.x + 10f, root.y - 7f, root.x, root.y)
        close()
    }
    // (x 간격, 기울기, 위로 올리는 정도)
    listOf(Triple(-11f, -30f, -3f), Triple(11f, 30f, -3f), Triple(0f, 0f, 3f)).forEach { (gap, angle, lift) ->
        translate(gap, -lift) {
            rotate(angle, root) {
                drawPath(leaf, SUN_RAY)
                drawPath(leaf, OUTLINE, style = Stroke(OUTLINE_W, join = StrokeJoin.Round))
            }
        }
    }
}

private fun DrawScope.drawNightCap() {
    // 머리 곡선을 감싸는 모자. 끝은 오른쪽으로 처짐
    val cap = Path().apply {
        moveTo(52f, 78f)
        cubicTo(54f, 48f, 76f, 28f, 104f, 28f)
        cubicTo(132f, 28f, 158f, 50f, 170f, 90f)
        cubicTo(162f, 82f, 154f, 78f, 148f, 78f)
        close()
    }
    drawPath(cap, NIGHT_CAP)
    drawPath(cap, OUTLINE, style = Stroke(OUTLINE_W, join = StrokeJoin.Round))
    // 달 (노란 원에 모자색 원을 겹침)
    drawCircle(SUN_RAY, 10f, Offset(96f, 50f))
    drawCircle(NIGHT_CAP, 9f, Offset(101f, 46f))
    // 챙: 이 높이의 머리 폭(양쪽 약 50~150)과 딱 맞춤
    drawRoundRect(Color.White, Offset(48f, 72f), Size(104f, 14f), CornerRadius(7f))
    drawRoundRect(OUTLINE, Offset(48f, 72f), Size(104f, 14f), CornerRadius(7f), style = Stroke(OUTLINE_W))
    // 방울
    drawCircle(SUN_RAY, 8f, Offset(170f, 94f))
    drawCircle(OUTLINE, 8f, Offset(170f, 94f), style = Stroke(OUTLINE_W))
}

// ---------------- 스트레칭 팔·손·다리 ----------------

private val WALL = Color(0xFFE9DCCB)
private val CUE = Color(0xFF3A2E2A).copy(alpha = 0.5f)
private const val MITTEN_W = 18f

/** 선 자세 좌표를 좌우로 뒤집음 (몸 중심 x = 100) */
private fun mirrorX(p: Offset) = Offset(200f - p.x, p.y)

/** a→b 방향 각도 (도, 오른쪽 0 · 아래 90) */
private fun angleOf(a: Offset, b: Offset) = Math.toDegrees(kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()

private fun polar(c: Offset, degrees: Float, length: Float): Offset {
    val r = Math.toRadians(degrees.toDouble())
    return Offset(c.x + cos(r).toFloat() * length, c.y + sin(r).toFloat() * length)
}

/**
 * 스트레칭 팔의 목표 위치 (오른팔로 하는 쪽 기준): 하는 팔(어깨 · 팔꿈치 · 손 끝)과 돕는 왼팔(어깨 · 팔꿈치 · 손).
 * WRIST_*의 하는 팔 끝은 손목 (손은 drawMitten으로 따로)
 */
private fun poseArmTargets(prop: NextyProp): Pair<List<Offset>, List<Offset>> = when (prop) {
    // 오른팔을 가슴 앞으로 곧게 가로질러 왼쪽 옆구리 밖까지, 왼팔은 아래에서 올라와 오른 팔꿈치를 걸어 당김
    NextyProp.CROSS_PULL ->
        listOf(Offset(150f, 136f), Offset(108f, 150f), Offset(44f, 150f)) to
            listOf(Offset(50f, 134f), Offset(46f, 176f), Offset(108f, 152f))
    // 오른팔을 앞으로 뻗어 손목이 배 가운데 앞에, 손끝을 위로 세운 큰 손바닥이 보임. 왼손은 손가락 끝을 잡음
    NextyProp.WRIST_UP ->
        listOf(Offset(150f, 134f), Offset(150f, 180f), Offset(104f, 184f)) to
            listOf(Offset(50f, 134f), Offset(52f, 170f), Offset(98f, 140f))
    // 손목이 가슴 가운데 앞에, 손끝을 아래로 내린 손등이 보임. 왼손은 손등을 눌러 줌
    else ->
        listOf(Offset(150f, 134f), Offset(154f, 166f), Offset(104f, 146f)) to
            listOf(Offset(50f, 134f), Offset(50f, 184f), Offset(98f, 176f))
}

/**
 * 스트레칭 팔 (CROSS_PULL·WRIST_UP·WRIST_DOWN). act 0이면 쉬는 자세의 팔, 1이면 목표 위치, 음수면 좌우를 뒤집음.
 * front=false: 두 팔을 몸 뒤에 / true: 몸 앞으로 오는 부분(위팔 중간부터)과 손을 다시 그림
 */
private fun DrawScope.drawPoseArms(prop: NextyProp, act: Float, front: Boolean) {
    val amount = abs(act).coerceIn(0f, 1f)
    val flip = act < 0f
    val (doT, helpT) = poseArmTargets(prop)
    val restDo = armPoints(false, 10f, 0f, 1.3f).toList()
    val restHelp = armPoints(true, 10f, 0f, 1.3f).toList()
    fun place(rest: List<Offset>, target: List<Offset>) =
        rest.indices.map { lerpOffset(rest[it], target[it], amount) }.map { if (flip) mirrorX(it) else it }
    val doArm = place(restDo, doT)
    val helpArm = place(restHelp, helpT)

    if (!front) {
        drawLimb(doArm, doArm)
        drawLimb(helpArm, helpArm)
        return
    }
    if (amount < 0.15f) return
    fun inFront(p: List<Offset>) = drawLimb(
        listOf(lerpOffset(p[0], p[1], 0.45f), p[1], p[2]),
        listOf(lerpOffset(p[0], p[1], 0.3f), p[1], p[2])
    )
    inFront(doArm)
    if (prop != NextyProp.CROSS_PULL) {
        // 돕는 팔 → 손바닥(손등) → 그 위에 돕는 손: 손이 가려지지 않고 손끝(손등)만 잡은 것처럼 보이게
        inFront(helpArm)
        val up = prop == NextyProp.WRIST_UP
        drawMitten(doArm[2], if (up) -90f else 90f, 30f * amount, palm = up, thumbRight = if (up) flip else !flip, k = 1.5f, armDegrees = angleOf(doArm[1], doArm[2]))
        drawCircle(OUTLINE, 7f + OUTLINE_W, helpArm[2])
        drawCircle(BODY, 7f, helpArm[2])
        return
    }
    // 어깨 교차 당기기: 당겨지는 어깨 뒤쪽의 늘어나는 선, 그 위에 팔꿈치를 거는 팔
    listOf(Offset(168f, 112f) to Offset(178f, 104f), Offset(174f, 128f) to Offset(186f, 126f), Offset(168f, 144f) to Offset(178f, 150f))
        .forEach { (a, b) ->
            val p = if (flip) mirrorX(a) else a
            val q = if (flip) mirrorX(b) else b
            drawLine(OUTLINE.copy(alpha = amount), p, q, OUTLINE_W * 0.8f, StrokeCap.Round)
        }
    inFront(helpArm)
}

/**
 * 손바닥 모양 손 (손목에서 손끝 방향 degrees로 length만큼). palm이면 손바닥(밝은 면과 손가락 사이 선),
 * 아니면 손등(손가락 마디 선). thumbRight: 손끝 방향을 위로 놓고 볼 때 엄지가 오른쪽.
 * k: 손 크기 배율 (선 굵기는 다른 선과 같게 그대로 둠). details=false면 손바닥·마디 표시 없이 모양만 (주먹).
 * 팔 끝에 이어 그리며, 손목과 손 사이에는 선을 긋지 않음 (윤곽선을 먼저 모두 칠하고 몸 색을 덮은 뒤 손목 자리를 팔 굵기로 다시 칠함).
 * armDegrees: 손목으로 들어오는 팔의 방향 (손목 자리를 이 방향을 따라 칠함)
 */
private fun DrawScope.drawMitten(
    wrist: Offset, degrees: Float, baseLength: Float, palm: Boolean, thumbRight: Boolean, k: Float = 1f,
    details: Boolean = true, armDegrees: Float = degrees
) {
    if (baseLength < 4f) return
    val length = baseLength * k
    val w = MITTEN_W * k * (0.6f + 0.4f * (baseLength / 26f).coerceIn(0f, 1f))
    rotate(degrees, wrist) {
        // 회전 뒤에는 손끝이 오른쪽(+x). 엄지는 손 옆 (+y가 회전 전 기준 손끝 방향의 오른쪽)
        val thumbY = if (thumbRight) w * 0.55f else -w * 0.55f
        val thumb = Offset(wrist.x + length * 0.32f, wrist.y + thumbY)
        val topLeft = Offset(wrist.x - 3f * k, wrist.y - w / 2f)
        val size = Size(length + 3f * k, w)
        drawLine(OUTLINE, wrist, thumb, 7f * k + OUTLINE_W * 2f, StrokeCap.Round)
        drawRoundRect(
            OUTLINE, topLeft - Offset(OUTLINE_W, OUTLINE_W), Size(size.width + OUTLINE_W * 2f, size.height + OUTLINE_W * 2f),
            CornerRadius(w / 2f + OUTLINE_W)
        )
        drawLine(BODY, wrist, thumb, 7f * k, StrokeCap.Round)
        drawRoundRect(BODY, topLeft, size, CornerRadius(w / 2f))
    }
    // 손목 이음새 지우기: 팔 쪽에서 손목을 지나 손 안쪽까지 팔 굵기로 몸 색을 이어 칠함
    drawLine(BODY, polar(wrist, armDegrees, -16f), wrist, ARM_W, StrokeCap.Round)
    drawLine(BODY, wrist, polar(wrist, degrees, 6f * k), ARM_W, StrokeCap.Round)
    if (!details) return
    rotate(degrees, wrist) {
        if (palm) {
            drawOval(BELLY, Offset(wrist.x + 1f, wrist.y - w * 0.3f), Size(length * 0.55f, w * 0.6f))
            listOf(-w / 6f, w / 6f).forEach { dy ->
                drawLine(OUTLINE.copy(alpha = 0.55f), Offset(wrist.x + length * 0.62f, wrist.y + dy), Offset(wrist.x + length - 1f, wrist.y + dy), 1.8f, StrokeCap.Round)
            }
        } else {
            drawLine(OUTLINE.copy(alpha = 0.55f), Offset(wrist.x + length * 0.58f, wrist.y - w * 0.32f), Offset(wrist.x + length * 0.58f, wrist.y + w * 0.32f), 2f, StrokeCap.Round)
        }
    }
}

/** 오른팔 끝의 손: 주먹 쥐었다 펴기(RAISED_HAND), 손목 돌리기(WRIST_CIRCLE) */
private fun DrawScope.drawHand(prop: NextyProp, pose: NextyPose) {
    if (prop != NextyProp.RAISED_HAND && prop != NextyProp.WRIST_CIRCLE) return
    val (_, mid, end) = armPoints(false, pose.rightShoulder, pose.rightElbow, pose.rightReach)
    val dir = angleOf(mid, end)
    if (prop == NextyProp.RAISED_HAND) {
        // 손목 늘이기와 같은 손: 펴면 손가락 선이 보이는 긴 손바닥, 쥐면 짧고 둥근 주먹
        val grip = pose.grip.coerceIn(0f, 1f)
        drawMitten(end, dir, lerp(24f, 12f, grip), palm = true, thumbRight = false, k = 1.3f, details = grip < 0.5f)
        return
    }
    // 손목을 축으로 손끝이 원을 그리며 돎: 옆으로 기울고, 화면 쪽·반대쪽으로 갈 때 짧아 보임. 손바닥·손등이 번갈아 보임
    val t = Math.toRadians(pose.wristTurn.toDouble())
    val handDir = dir + 40f * sin(t).toFloat()
    val length = 22f * (0.62f + 0.38f * ((cos(t).toFloat() + 1f) / 2f))
    // 손을 둘러싼 동그란 화살표
    val c = polar(end, dir, 12f)
    rotate(dir, c) {
        drawArc(CUE, 30f, 290f, false, Offset(c.x - 13f, c.y - 24f), Size(26f, 48f), style = Stroke(3f, cap = StrokeCap.Round))
        val tip = Offset(c.x + 13f * cos(Math.toRadians(320.0)).toFloat(), c.y + 24f * sin(Math.toRadians(320.0)).toFloat())
        drawArrowHead(tip, 230f)
    }
    drawMitten(end, handDir, length, palm = cos(t) >= 0.0, thumbRight = true, k = 1.25f)
}

/** 움직이는 방향을 알려 주는 화살표 (몸과 함께 눕고 기욺) */
private fun DrawScope.drawCue(prop: NextyProp, breath: Float) {
    val wobble = breath * 2f
    when (prop) {
        // 턱을 가슴 쪽으로: 얼굴 옆에서 아래로 내려가는 화살표
        NextyProp.CUE_CHIN -> drawCueArrow(Offset(176f, 88f + wobble), Offset(186f, 106f), Offset(174f, 126f + wobble), both = false)
        // 고개를 좌우로: 머리 위의 양쪽 화살표
        NextyProp.CUE_HEAD -> drawCueArrow(Offset(58f, 44f), Offset(100f, 14f - wobble), Offset(142f, 44f), both = true)
        // 골반을 앞뒤로: 엉덩이 옆의 양쪽 화살표
        NextyProp.CUE_PELVIS -> drawCueArrow(Offset(24f, 160f), Offset(8f - wobble, 184f), Offset(28f, 208f), both = true)
        else -> Unit
    }
}

private fun DrawScope.drawCueArrow(start: Offset, control: Offset, end: Offset, both: Boolean) {
    val path = Path().apply {
        moveTo(start.x, start.y)
        quadraticTo(control.x, control.y, end.x, end.y)
    }
    drawPath(path, CUE, style = Stroke(3f, cap = StrokeCap.Round))
    drawArrowHead(end, angleOf(control, end))
    if (both) drawArrowHead(start, angleOf(control, start))
}

private fun DrawScope.drawArrowHead(tip: Offset, degrees: Float) {
    listOf(150f, -150f).forEach { drawLine(CUE, tip, polar(tip, degrees + it, 8f), 3f, StrokeCap.Round) }
}

/**
 * 종아리 늘이기: 벽과 다리. act 0이면 평소 발, 1이면 왼쪽 벽 쪽 다리는 굽히고 오른 다리를 뒤로 쭉 빼 뒤꿈치를 붙임 (음수면 좌우 반대).
 * 다리 뿌리는 몸에 가려지고 몸 기울기를 따라감
 */
private fun DrawScope.drawCalfWall(pose: NextyPose) {
    val amount = abs(pose.act).coerceIn(0f, 1f)
    val flip = pose.act < 0f
    fun m(p: Offset) = if (flip) mirrorX(p) else p
    if (amount > 0f) {
        val left = if (flip) 212f else -26f
        drawRoundRect(WALL.copy(alpha = amount), Offset(left, 26f), Size(14f, 180f), CornerRadius(4f))
        drawRoundRect(OUTLINE.copy(alpha = amount), Offset(left, 26f), Size(14f, 180f), CornerRadius(4f), style = Stroke(OUTLINE_W * 0.8f))
    }
    val base = Offset(100f, 196f)
    fun tilted(p: Offset): Offset {
        val r = Math.toRadians(pose.tilt.toDouble())
        val d = p - base
        return Offset(base.x + d.x * cos(r).toFloat() - d.y * sin(r).toFloat(), base.y + d.x * sin(r).toFloat() + d.y * cos(r).toFloat())
    }
    val frontHip = tilted(m(Offset(84f, 180f)))
    val backHip = tilted(m(Offset(116f, 180f)))
    val frontFoot = m(lerpOffset(Offset(77f, 195f), Offset(62f, 197f), amount))
    val backFoot = m(lerpOffset(Offset(123f, 195f), Offset(196f, 199f), amount))
    val knee = lerpOffset(frontHip, frontFoot, 0.5f) + Offset(if (flip) 12f * amount else -12f * amount, 0f)
    drawLimb(listOf(frontHip, knee, frontFoot), listOf(frontHip, knee, frontFoot), 13f)
    drawLimb(listOf(backHip, backFoot), listOf(backHip, backFoot), 13f)
    listOf(frontFoot, backFoot).forEach { f ->
        drawOval(BODY, Offset(f.x - 15f, f.y - 9f), Size(30f, 18f))
        drawOval(OUTLINE, Offset(f.x - 15f, f.y - 9f), Size(30f, 18f), style = Stroke(OUTLINE_W))
    }
    // 뒤로 뺀 다리 종아리가 늘어나는 선
    if (amount > 0.5f) {
        val a = (amount - 0.5f) * 2f
        val calf = lerpOffset(backHip, backFoot, 0.68f)
        val dir = angleOf(backHip, backFoot)
        listOf(-7f, 0f, 7f).forEach { k ->
            val p = polar(polar(calf, dir, k), dir - 90f, 13f)
            drawLine(OUTLINE.copy(alpha = a), p, polar(p, dir - 90f, 7f), OUTLINE_W * 0.8f, StrokeCap.Round)
        }
    }
}

// ---------------- 소품 ----------------

/** 몸과 함께 기울지 않는 효과: 해, 스트레칭 선, 반짝임 */
private fun DrawScope.drawBackgroundProp(prop: NextyProp, breath: Float) {
    when (prop) {
        NextyProp.SUN -> drawSun(Offset(24f, 56f), 13f + breath * 1.5f)
        NextyProp.STRETCH_LINES -> {
            val pull = breath * 3f
            listOf(
                Offset(-8f, 96f) to Offset(6f, 102f), Offset(-12f, 122f) to Offset(4f, 122f), Offset(-8f, 148f) to Offset(6f, 142f),
                Offset(208f, 96f) to Offset(194f, 102f), Offset(212f, 122f) to Offset(196f, 122f), Offset(208f, 148f) to Offset(194f, 142f)
            ).forEach { (a, b) ->
                val dir = if (a.x < 100f) -pull else pull
                drawLine(OUTLINE, a + Offset(dir, 0f), b + Offset(dir, 0f), OUTLINE_W, StrokeCap.Round)
            }
        }
        NextyProp.SPARKLES -> {
            drawSparkle(Offset(12f, 96f), 11f + breath * 3f)
            drawSparkle(Offset(190f, 62f), 8f + (1f - breath) * 3f)
        }
        NextyProp.ARM_CIRCLE -> {   // 동그라미 팔 바깥의 반짝임
            drawSparkle(Offset(4f, 60f), 9f + breath * 3f)
            drawSparkle(Offset(200f, 120f), 8f + (1f - breath) * 3f)
        }
        NextyProp.WELCOME_SPARKLES -> {
            drawSparkle(Offset(2f, 70f), 8f + breath * 3f)
            drawSparkle(Offset(200f, 36f), 11f + (1f - breath) * 3f)
            drawSparkle(Offset(204f, 104f), 7f + breath * 2f, BLUSH)
        }
        else -> Unit
    }
}

/** 두 손 사이에 드는 소품. 손을 따라 움직임 */
private fun DrawScope.drawHeldProp(prop: NextyProp, pose: NextyPose) {
    if (prop != NextyProp.CUP && prop != NextyProp.MEMO) return
    val left = armPoints(true, pose.leftShoulder, pose.leftElbow, pose.leftReach).third
    val right = armPoints(false, pose.rightShoulder, pose.rightElbow, pose.rightReach).third
    val center = lerpOffset(left, right, 0.5f)
    if (prop == NextyProp.CUP) drawCup(center) else drawMemo(center)
}

private fun DrawScope.drawCup(c: Offset) {
    val cup = Path().apply {
        moveTo(c.x - 14f, c.y - 18f)
        lineTo(c.x + 14f, c.y - 18f)
        lineTo(c.x + 11f, c.y + 16f)
        lineTo(c.x - 11f, c.y + 16f)
        close()
    }
    drawPath(cup, Color.White)
    drawRect(WATER, Offset(c.x - 11.5f, c.y - 6f), Size(23f, 21f))
    drawPath(cup, OUTLINE, style = Stroke(OUTLINE_W, join = StrokeJoin.Round))
}

private fun DrawScope.drawMemo(c: Offset) {
    val topLeft = Offset(c.x - 28f, c.y - 28f)
    val size = Size(56f, 50f)
    drawRoundRect(Color.White, topLeft, size, CornerRadius(5f))
    drawRoundRect(OUTLINE, topLeft, size, CornerRadius(5f), style = Stroke(OUTLINE_W))
    val check = Path().apply {
        moveTo(c.x - 20f, c.y - 8f)
        lineTo(c.x - 12f, c.y)
        lineTo(c.x + 2f, c.y - 16f)
    }
    drawPath(check, OUTLINE, style = Stroke(OUTLINE_W, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(OUTLINE, Offset(c.x + 7f, c.y - 12f), Offset(c.x + 20f, c.y - 12f), 3f, StrokeCap.Round)
    drawLine(OUTLINE, Offset(c.x + 7f, c.y - 2f), Offset(c.x + 20f, c.y - 2f), 3f, StrokeCap.Round)
}

private fun DrawScope.drawSun(c: Offset, radius: Float) {
    repeat(8) { i ->
        val r = Math.toRadians(i * 45.0)
        val dir = Offset(sin(r).toFloat(), cos(r).toFloat())
        drawLine(SUN_RAY, c + dir * (radius + 6f), c + dir * (radius + 14f), 4f, StrokeCap.Round)
    }
    drawCircle(SUN_RAY, radius, c)
}

/** 끝이 네 개인 반짝임 */
private fun DrawScope.drawSparkle(c: Offset, r: Float, color: Color = SUN_RAY) {
    val k = r * 0.22f
    val star = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
        quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
        quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
        quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
        close()
    }
    drawPath(star, color)
}

// ---------------- 팔·다리 ----------------

/** 팔의 세 점: 어깨 · 팔꿈치 · 손 */
private fun armPoints(left: Boolean, shoulder: Float, elbow: Float, reach: Float): Triple<Offset, Offset, Offset> {
    val side = if (left) -1f else 1f
    val start = Offset(100f + side * SHOULDER_X, SHOULDER_Y)
    val mid = start + armVector(side, shoulder, UPPER_ARM * min(reach, MAX_UPPER_REACH))
    return Triple(start, mid, mid + armVector(side, shoulder + elbow, LOWER_ARM * reach))
}

/** 어깨에서 시작하는 두 마디 팔. 윤곽선 굵기로 먼저 그린 위에 몸 색을 덧그림. 어깨 쪽은 몸에 가려짐 */
private fun DrawScope.drawArmBehind(left: Boolean, shoulder: Float, elbow: Float, reach: Float) {
    val (start, mid, end) = armPoints(left, shoulder, elbow, reach)
    drawLimb(listOf(start, mid, end), listOf(start, mid, end))
}

/** 팔 끝이 몸 쪽을 향하면 팔꿈치 아래만 몸 앞에 다시 그림. 위팔 일부에 몸 색을 이어 칠해 팔꿈치에 선이 생기지 않게 함 */
private fun DrawScope.drawForearmInFront(left: Boolean, shoulder: Float, elbow: Float, reach: Float) {
    if (sin(Math.toRadians((shoulder + elbow).toDouble())) >= 0.0) return
    val (start, mid, end) = armPoints(left, shoulder, elbow, reach)
    drawLimb(listOf(mid, end), listOf(lerpOffset(start, mid, 0.55f), mid, end))
}

private fun lerpOffset(a: Offset, b: Offset, t: Float) = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

private fun DrawScope.drawLimb(outline: List<Offset>, fill: List<Offset>, width: Float = ARM_W) {
    fun path(points: List<Offset>) = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path(outline), OUTLINE, style = Stroke(width + OUTLINE_W * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path(fill), BODY, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun armVector(side: Float, degrees: Float, length: Float): Offset {
    val r = Math.toRadians(degrees.toDouble())
    return Offset(side * sin(r).toFloat() * length, cos(r).toFloat() * length)
}

private fun DrawScope.drawFeet() {
    listOf(62f, 108f).forEach { x ->
        drawOval(BODY, Offset(x, 186f), Size(30f, 18f))
        drawOval(OUTLINE, Offset(x, 186f), Size(30f, 18f), style = Stroke(OUTLINE_W))
    }
}

private fun DrawScope.drawCrossedLegs() {
    listOf(
        Offset(52f, 188f) to Offset(122f, 206f),
        Offset(148f, 188f) to Offset(78f, 206f)
    ).forEach { (a, b) ->
        drawLine(OUTLINE, a, b, 16f + OUTLINE_W * 2f, StrokeCap.Round)
        drawLine(BODY, a, b, 16f, StrokeCap.Round)
    }
}
