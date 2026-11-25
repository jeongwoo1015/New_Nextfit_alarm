package com.example.respeclive.ui.nexty

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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
import androidx.compose.ui.graphics.ClipOp
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
private val ZZ_COLOR = Color(0xFFE6E2FF)

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
private const val CROSS_ARM_DROP = 5f      // 오답 X 팔을 어깨 높이에서 내린 정도

/** 표정 (눈과 입만 바뀜) */
enum class NextyFace { NORMAL, HAPPY, GRIN, SLEEPY, FOCUS, WINCE, FIGHT }

/**
 * 동작에 함께 그리는 소품·효과.
 * RAISED_HAND·WRIST_CIRCLE: 오른팔 끝에 손 모양 (주먹·편 손 / 돌아가는 손목).
 * CUE_*: 움직이는 방향을 알려 주는 화살표. CROSS_PULL·WRIST_UP·WRIST_DOWN: 팔을 자세 대신 따로 그림 (act로 진행).
 * CALF_WALL: 벽과 뒤로 뺀 다리 (act로 진행).
 * CUE_SHRUG: 어깨 옆의 위아래 화살표. TWIST_HUG: 두 손으로 반대쪽 어깨를 감싼 팔과 고개 화살표.
 * LEG_*: 앉은 자세에서 양반다리 대신 따로 그리는 다리 (act로 진행, 음수면 반대쪽 다리)
 */
enum class NextyProp {
    NONE, STRETCH_LINES, WELCOME_SPARKLES, ARM_CIRCLE, ARM_CROSS, FIGHT_FISTS, BACK_VIEW, BLADE_FRONT,
    RAISED_HAND, WRIST_CIRCLE, CUE_CHIN, CUE_HEAD, CUE_PELVIS, CROSS_PULL, WRIST_UP, WRIST_DOWN, CALF_WALL,
    CUE_SHRUG, TWIST_HUG, LEG_FIGURE_FOUR, LEG_TOE_PULL, LEG_OUT_TOWEL, LEG_ANKLE_CIRCLE
}

/**
 * 몸 자세. 누운 자세(LIE, SIDE)는 선 자세 그대로 그린 뒤 머리가 왼쪽으로 가게 통째로 눕힘.
 * 눕히면 몸의 오른쪽(오른팔 쪽)이 화면 위쪽, 아래쪽(발 쪽)이 화면 오른쪽이 됨.
 * LIE는 천장에서 비스듬히 내려다본 모습: 몸과 두 다리가 화면을 향하고, 천장 쪽으로 든 무릎·다리·팔은 화면 위쪽으로 보임.
 * SIDE는 화면 쪽을 보고 옆으로 누운 모습을 앞 위에서 본 것: 화면 쪽으로 굽힌 무릎은 아래쪽, 몸 뒤쪽은 위쪽으로 보임.
 * ALL4(네발로)·KNEEL(무릎 꿇고)은 바닥 위를 옆에서 본 모습: 머리가 왼쪽인 옆모습 몸통(눈 하나인 옆얼굴)에 팔다리를 바닥에 내림 (drawOnFloor)
 */
enum class NextyPosture { STAND, SIT, LIE, SIDE, ALL4, KNEEL }

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
 * forearmsBehind면 팔꿈치 아래를 몸 앞에 다시 그리지 않음 (머리 뒤·등 뒤로 넘긴 손).
 * shrug는 두 팔을 위로 올린 정도 (어깨 으쓱), arch는 네발 자세의 등 모양 (+1: 등을 둥글게 말음, -1: 배를 내림).
 * 앉은 자세의 LEG_* 동작에서는 nearAnkle이 발끝을 당긴 각도, wristTurn이 발목을 돌린 각도
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
    val forearmsBehind: Boolean = false,
    val shrug: Float = 0f,
    val arch: Float = 0f,
    val lift: Float = 0f,          // 선 자세에서 뒤꿈치를 들어 몸이 올라간 정도
    val lean: Float = 0f           // 선 자세에서 왼발(화면 왼쪽)을 축으로 몸 전체를 기울인 각도 (-: 왼쪽, 오른발이 같이 뜸)
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
    forearmsBehind = if (t < 0.5f) a.forearmsBehind else b.forearmsBehind,
    shrug = lerp(a.shrug, b.shrug, t),
    arch = lerp(a.arch, b.arch, t),
    lift = lerp(a.lift, b.lift, t),
    lean = lerp(a.lean, b.lean, t)
)

/**
 * 자세로 이동하는 데 걸리는 시간. 같은 자세를 다시 넣으면 그만큼 유지됨.
 * face가 있으면 이 단계 동안 그 표정(없으면 화면이 정한 표정), zz면 머리 옆에 Zz를 띄움 (잠든 모습).
 * prop이 있으면 이 단계 동안 그 소품 (없으면 동작의 소품. 홈 화면에서 파이팅 주먹·강조선을 그릴 때)
 */
data class NextyStep(
    val pose: NextyPose,
    val ms: Int,
    val easing: Easing = FastOutSlowInEasing,
    val face: NextyFace? = null,
    val zz: Boolean = false,
    val prop: NextyProp? = null
)

/** 반복되는 동작. posture는 몸 자세(서서·양반다리·누워서·옆으로 누워서), face는 이 동작의 기본 표정 */
enum class NextyMotion(
    val label: String,
    val posture: NextyPosture,
    val face: NextyFace,
    val prop: NextyProp,
    val steps: List<NextyStep>
) {
    IDLE("기본", NextyPosture.STAND, NextyFace.NORMAL, NextyProp.NONE, listOf(NextyStep(NextyPose(), 1000))),
    // 홈 화면(낮): 기본 표정으로 바로 서 있는 시간이 대부분이고, 간간이 인사·파이팅·뒤꿈치 들기를 불규칙하게 함.
    // 화면에서는 homeDaySegment로 매번 새로 고름. 여기 단계는 검사용 그림과 대표 순서
    HOME_DAY("홈(낮)", NextyPosture.STAND, NextyFace.NORMAL, NextyProp.NONE, homeDaySample()),
    // 홈 화면(밤): 수면모자를 쓴 기본 표정이 대부분이고, 가끔 잠들기(눈 감고 Zz)·졸기·뒤꿈치 들기를 함 (homeNightSegment)
    HOME_NIGHT("홈(밤)", NextyPosture.STAND, NextyFace.NORMAL, NextyProp.NONE, homeNightSample()),
    WELCOME(
        "반기기", NextyPosture.STAND, NextyFace.GRIN, NextyProp.WELCOME_SPARKLES,
        listOf(
            NextyStep(greetPose(154f, lean = 0f), 400, SWAY),
            NextyStep(greetPose(122f, lean = 0f), 400, SWAY)
        )
    ),
    // 로그인 화면 인사: 서 있기 → 인사하기(왼쪽으로 기울어 왼발로 서서 짧은 오른팔 흔들기) → 뒤꿈치 살짝 들기를 차례로 반복
    HELLO("인사", NextyPosture.STAND, NextyFace.GRIN, NextyProp.NONE, helloSteps()),
    WAVE(
        "손 흔들기", NextyPosture.STAND, NextyFace.GRIN, NextyProp.NONE,
        listOf(
            NextyStep(greetPose(154f, lean = 0f), 400, SWAY),
            NextyStep(greetPose(122f, lean = 0f), 400, SWAY)
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
    // 파이팅 ("아자자자!!"): 한 주먹은 얼굴 옆으로 번쩍, 다른 주먹은 배 앞에. 힘준 눈 > <과 외치는 입, 둘레에 강조선 (FIGHT_FISTS)
    FIGHTING("파이팅", NextyPosture.STAND, NextyFace.FIGHT, NextyProp.FIGHT_FISTS, fightingSteps()),
    // ---- 아침 스트레칭: ai/tasks/stretch_sets.py의 동작 34개(MOVE_NAMES)와 이름(label)이 같음. 안내 문장의 순서·머무는 시간을 따라 움직임 ----
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
        listOf(NextyStep(wristRollArmPose(), 900)) +
            listOf(180f, 360f, 540f, 720f, 540f, 360f, 180f, 0f).map { NextyStep(wristRollArmPose().copy(wristTurn = it), 650) }
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
    // ---- 11/18 신규 동작 (stretch_sets.py NEW_MOVE_NAMES 11개) ----
    // 고개를 앞으로 숙여 얼굴이 아래로 내려감 (턱 당기기와 같은 화살표)
    NECK_NOD(
        "목 앞으로 숙이기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.CUE_CHIN,
        List(5) {
            listOf(
                NextyStep(relaxedPose().copy(faceY = 11f, stretch = 0.97f), 800),
                NextyStep(relaxedPose().copy(faceY = 11f, stretch = 0.97f), 2000),
                NextyStep(relaxedPose(), 800)
            )
        }.flatten()
    ),
    // 두 팔(어깨)이 귀 쪽으로 올라가 3초 멈췄다가 툭 내려감
    SHRUG(
        "어깨 으쓱 내리기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.CUE_SHRUG,
        relaxedPose().copy(shrug = 12f, stretch = 0.97f).let { up ->
            List(4) {
                listOf(NextyStep(up, 600), NextyStep(up, 1500), NextyStep(relaxedPose(), 300), NextyStep(relaxedPose(), 1100))
            }.flatten()
        }
    ),
    // 앞모습(BLADE_FRONT)으로 두 팔을 들어 팔꿈치를 굽혀 손을 위로(골대 모양) 보인 뒤, 뒷모습(BACK_VIEW)으로 돌아
    // 팔꿈치를 아래 뒤로 당기며 날개뼈를 모아 머묾. act가 날개뼈를 모은 정도 (등의 날개뼈 선이 가운데로 모임)
    BLADE_SQUEEZE(
        "날개뼈 모으기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.BLADE_FRONT,
        listOf(NextyStep(relaxedPose(), 500), NextyStep(bladeOpenPose(), 1000), NextyStep(bladeOpenPose(), 1600)) +
            NextyStep(bladeOpenPose(), 400, prop = NextyProp.BACK_VIEW) +
            List(3) {
                listOf(NextyStep(bladeSqueezePose(), 900), NextyStep(bladeSqueezePose(), 2400), NextyStep(bladeOpenPose(), 900), NextyStep(bladeOpenPose(), 500))
                    .map { it.copy(prop = NextyProp.BACK_VIEW) }
            }.flatten() + NextyStep(relaxedPose(), 900, prop = NextyProp.BACK_VIEW)
    ),
    // 두 팔을 옆으로 벌리고 가슴을 앞과 위로 밂 (몸이 늘어나고 얼굴이 살짝 들림)
    CHEST_OPEN(
        "가슴 펴기", NextyPosture.SIT, NextyFace.HAPPY, NextyProp.NONE,
        chestOpenPose().let { open ->
            List(3) {
                listOf(NextyStep(open, 1000), NextyStep(open, 2600), NextyStep(relaxedPose(), 900), NextyStep(relaxedPose(), 500))
            }.flatten()
        }
    ),
    // 짧은 두 팔을 가슴 앞에 모으고, 상체를 좌우로 돌림 (얼굴이 옆으로 옮겨 감)
    SIT_TWIST(
        "앉아서 상체 비틀기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.TWIST_HUG,
        holdBothSides(relaxedPose().copy(faceX = -15f, tilt = -3f), reachMs = 1000, holdMs = 2200)
    ),
    // 네발로: 숨을 들이쉬며 등을 둥글게 말고(고개 숙임), 내쉬며 배를 내리고 가슴을 앞으로(고개 듦)
    CAT_COW(
        "고양이 소 자세", NextyPosture.ALL4, NextyFace.FOCUS, NextyProp.NONE,
        List(4) {
            listOf(
                NextyStep(relaxedPose().copy(arch = 1f, tilt = -7f), 1500), NextyStep(relaxedPose().copy(arch = 1f, tilt = -7f), 600),
                NextyStep(relaxedPose().copy(arch = -1f, tilt = 7f), 1500), NextyStep(relaxedPose().copy(arch = -1f, tilt = 7f), 600)
            )
        }.flatten()
    ),
    // 무릎 꿇고 앉았다가(act 0) 엉덩이를 뒤꿈치로 보내며 상체를 숙이고 두 팔을 앞으로 뻗음(act 1). 숨 6번 머묾
    CHILD_POSE(
        "아기 자세", NextyPosture.KNEEL, NextyFace.FOCUS, NextyProp.NONE,
        listOf(
            NextyStep(relaxedPose(), 1000),
            NextyStep(relaxedPose().copy(act = 1f), 1800)
        ) + List(6) {
            listOf(NextyStep(relaxedPose().copy(act = 1f, stretch = 1.03f), 1300), NextyStep(relaxedPose().copy(act = 1f), 1300))
        }.flatten() + NextyStep(relaxedPose(), 1500)
    ),
    // 침대 끝에 걸터앉아 오른쪽 발목을 왼쪽 무릎 위에 올리고(정강이가 무릎 위를 가로지름) 상체를 살짝 앞으로 숙임
    SIT_FIGURE_FOUR(
        "앉아서 4자 스트레칭", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.LEG_FIGURE_FOUR,
        holdBothSides(relaxedPose().copy(act = 1f, stretch = 0.95f, faceY = 5f), reachMs = 1300, holdMs = 6000)
    ),
    // 한쪽 다리를 펴고 수건으로 발 앞쪽을 몸 쪽으로 당김 (상체는 다리 쪽으로 살짝 기욺)
    SIT_HAMSTRING(
        "앉아서 다리 뒤쪽 늘이기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.LEG_OUT_TOWEL,
        holdBothSides(
            relaxedPose().copy(act = 1f, tilt = 6f, rightShoulder = 31f, rightElbow = 0f, rightReach = 1.05f),
            reachMs = 1300, holdMs = 6000
        )
    ),
    // 오른 발목을 왼 무릎에 올리고(4자), 왼손을 내려 발가락을 잡아 정강이 쪽(위)으로 당겨 머묾. 반대쪽도.
    // 팔은 보통 팔처럼 그림(위팔은 몸 뒤, 아래팔은 몸 앞). 손 끝의 주먹은 발 위에 drawBedLegs가 그림
    SIT_TOE_PULL(
        "앉아서 발끝 당기기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.LEG_TOE_PULL,
        toePullPose(pulled = false).let { hold ->
            val pulled = toePullPose(pulled = true)
            listOf(hold to pulled, hold.mirrored() to pulled.mirrored()).flatMap { (h, p) ->
                listOf(NextyStep(h, 1200), NextyStep(p, 1100), NextyStep(p, 5000), NextyStep(h, 800), NextyStep(relaxedPose(), 900))
            }
        }
    ),
    // 한쪽 다리를 옆으로 곧게 펴서 살짝 들고, 다리와 직각으로 선 발의 발끝이 바깥쪽·안쪽으로 작은 원을 그림
    SIT_ANKLE_ROLL(
        "앉아서 발목 돌리기", NextyPosture.SIT, NextyFace.FOCUS, NextyProp.LEG_ANKLE_CIRCLE,
        relaxedPose().copy(act = 1f).let { lifted ->
            listOf(lifted, lifted.mirrored()).flatMap { l ->
                listOf(NextyStep(l, 1100)) +
                    listOf(180f, 360f, 540f, 720f, 540f, 360f, 180f, 0f).map { NextyStep(l.copy(wristTurn = it), 650) } +
                    NextyStep(relaxedPose(), 900)
            }
        }
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


// ---- 인사(HELLO): 서 있기 → 인사하기 → 뒤꿈치 들기를 차례로 ----
private const val HELLO_LIFT = 7f           // 뒤꿈치를 들어 몸이 올라가는 정도
private const val HELLO_REACH = 1.15f       // 흔드는 팔 길이 배율 (짧은 팔이 귀여워서 조금만 늘림)
private const val HELLO_LEAN = -11f         // 인사할 때 왼발을 축으로 몸을 대각선으로 기울임 (오른발이 같이 뜸)
private val LEAN_PIVOT = Offset(77f, 203f)  // 왼발 바닥 가운데
private val SWAY = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)     // 흔들기: 양 끝에서 천천히 멈췄다 도는 부드러운 곡선

// 짧은 오른팔을 곧게 펴 어깨 위로 듦 (팔꿈치를 굽히지 않고 팔 전체를 흔듦). 손 흔들기는 모두 이 자세를 씀.
// lean: 로그인 인사는 왼발을 축으로 몸을 대각선으로 기울임(HELLO_LEAN), 다른 화면은 바로 선 채 0
private fun greetPose(shoulder: Float, lean: Float = HELLO_LEAN) = NextyPose(
    lean = lean, rightShoulder = shoulder, rightElbow = 0f, rightReach = HELLO_REACH
)

/** 인사하기: 기울며 오른팔을 들고 어깨를 축으로 부드럽게 네 번 흔든 뒤 천천히 섬 */
private fun greetSteps(): List<NextyStep> =
    listOf(NextyStep(greetPose(138f), 520)) +
        List(4) { i -> NextyStep(greetPose(if (i % 2 == 0) 154f else 122f), 400, SWAY) } +
        NextyStep(greetPose(138f), 220, SWAY) +
        NextyStep(NextyPose(), 520)

/** 뒤꿈치를 살짝 들었다 내리기 두 번 */
private fun heelSteps(): List<NextyStep> = List(2) {
    listOf(
        NextyStep(NextyPose(lift = HELLO_LIFT, stretch = 1.02f), 220),
        NextyStep(NextyPose(lift = HELLO_LIFT, stretch = 1.02f), 140),
        NextyStep(NextyPose(), 260)
    )
}.flatten()

// 파이팅 ("아자자자!!"): 왼팔(화면 왼쪽)은 팔꿈치를 굽혀 주먹을 얼굴 옆으로 번쩍, 오른팔은 팔꿈치를 접어 주먹을 가슴에 모음.
// up이면 위 주먹이 조금 더 올라가고 몸도 살짝 들림
private fun fightPose(up: Boolean) = if (up) {
    NextyPose(leftShoulder = 152f, leftElbow = 22f, leftReach = 1.25f, rightShoulder = 42f, rightElbow = -162f, rightReach = 1.3f,
        stretch = 1.03f, lift = 3f, tilt = 2f)
} else {
    NextyPose(leftShoulder = 138f, leftElbow = 34f, leftReach = 1.25f, rightShoulder = 40f, rightElbow = -160f, rightReach = 1.3f, tilt = 2f)
}

/** 파이팅: 위 주먹을 세 번 힘차게 들썩이고 잠깐 멈춤 */
private fun fightingSteps(): List<NextyStep> =
    listOf(NextyStep(fightPose(up = false), 380)) +
        List(3) { listOf(NextyStep(fightPose(up = true), 150), NextyStep(fightPose(up = false), 190)) }.flatten() +
        NextyStep(fightPose(up = false), 600)

/** 홈 화면의 파이팅: 주먹을 쥐고 들썩인 뒤 바로 섬 (파이팅 표정·주먹·강조선은 이 단계들에만) */
private fun homeFightSteps(): List<NextyStep> =
    fightingSteps().map { it.copy(face = NextyFace.FIGHT, prop = NextyProp.FIGHT_FISTS) } +
        NextyStep(NextyPose(), 420, face = NextyFace.NORMAL)

/** 표정이 없는 단계에 표정을 붙임 */
private fun List<NextyStep>.withFace(face: NextyFace) = map { if (it.face == null) it.copy(face = face) else it }

/** 기본 표정으로 바로 서 있기 */
private fun standStep(ms: Int) = NextyStep(NextyPose(), ms, face = NextyFace.NORMAL)

// ---- 홈 화면 낮·밤 ----
/** 낮 한 토막: 오래 서 있다가 가끔 한 동작. 서 있는 시간이 대부분이 되도록 동작 없이 넘어가는 토막도 둠 */
internal fun homeDaySegment(r: Random): List<NextyStep> {
    val stand = standStep(3000 + r.nextInt(3500))
    return listOf(stand) + when (r.nextInt(10)) {
        in 0..1 -> emptyList()                                       // 계속 서 있기
        in 2..4 -> homeFightSteps()                                  // 파이팅
        in 5..7 -> greetSteps().withFace(NextyFace.GRIN)             // 인사 (몸을 기울여 손 흔들기)
        else -> heelSteps().withFace(NextyFace.HAPPY)                // 뒤꿈치 들기
    }
}

private val DOZE_EASE = CubicBezierEasing(0.2f, 0f, 0.4f, 1f)      // 졸 때 천천히 기울다 멈춤

// 잠든 자세: 몸이 살짝 처지고 얼굴이 조금 내려감
private fun sleepPose(deep: Float = 1f) = NextyPose(stretch = 1f - 0.03f * deep, faceY = 2.5f * deep, tilt = 2f * deep)

/** 잠들기: 눈이 감기며 몸이 처지고 Zz가 뜬 채 한동안 잠 → 깨어 기본 표정 */
private fun sleepSteps(r: Random): List<NextyStep> = listOf(
    NextyStep(sleepPose(0.5f), 900, face = NextyFace.SLEEPY),
    NextyStep(sleepPose(), 1100, face = NextyFace.SLEEPY, zz = true),
    NextyStep(sleepPose(), 3500 + r.nextInt(3500), face = NextyFace.SLEEPY, zz = true),
    NextyStep(NextyPose(), 700, face = NextyFace.NORMAL)
)

/** 졸기: 눈을 감고 천천히 몸이 기울다가 퍼뜩 깨어 바로 섬, 1~2번 */
private fun dozeSteps(r: Random): List<NextyStep> = List(1 + r.nextInt(2)) {
    listOf(
        NextyStep(NextyPose(), 300, face = NextyFace.SLEEPY),
        NextyStep(NextyPose(tilt = 7f, stretch = 0.97f, faceY = 3f), 1600 + r.nextInt(600), DOZE_EASE, NextyFace.SLEEPY),
        NextyStep(NextyPose(tilt = -1.5f, stretch = 1.01f), 220, face = NextyFace.NORMAL),
        NextyStep(NextyPose(), 500, face = NextyFace.NORMAL)
    )
}.flatten()

/** 밤 한 토막: 수면모자를 쓴 채 오래 서 있다가 가끔 잠들기·졸기·뒤꿈치 들기 */
internal fun homeNightSegment(r: Random): List<NextyStep> {
    val stand = standStep(3000 + r.nextInt(3500))
    return listOf(stand) + when (r.nextInt(10)) {
        in 0..1 -> emptyList()
        in 2..4 -> sleepSteps(r)
        in 5..7 -> dozeSteps(r)
        else -> heelSteps().withFace(NextyFace.NORMAL)
    }
}

/** 검사용 대표 순서 */
private fun homeDaySample() = listOf(standStep(1000)) + homeFightSteps() + greetSteps().withFace(NextyFace.GRIN) +
    heelSteps().withFace(NextyFace.HAPPY)
private fun homeNightSample() = listOf(standStep(1000)) + sleepSteps(Random(1)) + dozeSteps(Random(3)) +
    heelSteps().withFace(NextyFace.NORMAL)

/** 서 있기 → 인사하기 → 서 있기 → 뒤꿈치 들기 (규칙적으로 반복) */
private fun helloSteps(): List<NextyStep> =
    listOf(NextyStep(NextyPose(), 900)) + greetSteps() + NextyStep(NextyPose(), 900) + heelSteps()


// 두 팔을 머리 위로 모으기
private fun armsUpPose() = NextyPose(
    leftShoulder = 165f, leftElbow = 60f, rightShoulder = 165f, rightElbow = 60f, leftReach = 4.4f, rightReach = 4.4f
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
private fun ceilingArmPose() = relaxedPose().copy(rightShoulder = 90f, rightElbow = 0f, rightReach = 1.2f)

// 손목 돌리기: 천장으로 뻗은 팔을 조금 짧게 (손과 둘레 화살표가 화면 위로 잘리지 않게)
private fun wristRollArmPose() = ceilingArmPose().copy(rightReach = 0.8f)

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

// 날개뼈 모으기(뒷모습): 두 팔을 어깨 높이로 들고 팔꿈치를 굽혀 손을 위로 (골대 모양)
private fun bladeOpenPose() = NextyPose(
    leftShoulder = 96f, leftElbow = 84f, leftReach = 1.35f, rightShoulder = 96f, rightElbow = 84f, rightReach = 1.35f
)

// 날개뼈 모으기: 팔꿈치를 아래 뒤로 당겨 날개뼈를 모음 (손은 위를 향한 채, 가슴이 열려 몸이 살짝 늘어남)
private fun bladeSqueezePose() = NextyPose(
    leftShoulder = 74f, leftElbow = 104f, leftReach = 1.35f, rightShoulder = 74f, rightElbow = 104f, rightReach = 1.35f,
    stretch = 1.03f, act = 1f
)

// 두 팔을 옆으로 벌리고 가슴을 앞과 위로 (얼굴이 살짝 들림)
private fun chestOpenPose() = relaxedPose().copy(
    leftShoulder = 100f, leftElbow = 8f, leftReach = 2.2f, rightShoulder = 100f, rightElbow = 8f, rightReach = 2.2f,
    stretch = 1.05f, faceY = -3f
)

// 발끝 당기기: 짧은 왼팔을 몸 앞으로 내려 무릎 위 발의 발끝을 잡음 (팔꿈치까지 몸 윤곽 안에 두어 몸 색이 윤곽선 밖으로 나오지 않게).
// 각도는 손이 발끝(쉴 때 바깥 아래, 당기면 위)을 잡도록 맞춘 값 (발목~발끝이 손과 무릎 사이에 보이게)
private fun toePullPose(pulled: Boolean) = relaxedPose().copy(
    act = 1f, faceY = 4f, leftReach = 1.5f,
    leftShoulder = if (pulled) -45f else -9f, leftElbow = if (pulled) -2f else -41f, nearAnkle = if (pulled) 1f else 0f
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
    var stepFace by remember { mutableStateOf<NextyFace?>(null) }
    var stepProp by remember { mutableStateOf<NextyProp?>(null) }
    var zz by remember { mutableStateOf(false) }

    LaunchedEffect(motion) {
        while (true) {
            // 홈 화면 낮·밤은 매번 다른 토막을 골라 불규칙하게
            val steps = when (motion) {
                NextyMotion.HOME_DAY -> homeDaySegment(Random.Default)
                NextyMotion.HOME_NIGHT -> homeNightSegment(Random.Default)
                else -> motion.steps
            }
            for (step in steps) {
                from = lerp(from, to, progress.value)
                to = step.pose
                stepFace = step.face
                stepProp = step.prop
                zz = step.zz
                progress.snapTo(0f)
                progress.animateTo(1f, tween(step.ms, easing = step.easing))
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
        drawNextyFrame(
            motion, lerp(from, to, progress.value), stepFace ?: face, night, breath, eyeOpen,
            // 말하는 동안은 다물어도 말하는 입 모양을 유지 (0이면 표정의 기본 입)
            mouthOpen = if (speaking) maxOf(mouth.value, 0.001f) else mouth.value,
            zz = zz, prop = stepProp ?: motion.prop
        )
    }
}

/** 캔버스 크기에 맞춰 넥스티 한 순간을 그림 (화면과 검사용 그림이 같은 코드를 씀) */
private fun DrawScope.drawNextyFrame(
    motion: NextyMotion, pose: NextyPose, face: NextyFace, night: Boolean, breath: Float, eyeOpen: Float, mouthOpen: Float,
    zz: Boolean = false, prop: NextyProp = motion.prop
) {
    val s = min(size.width / CANVAS_W, size.height / CANVAS_H)
    translate((size.width - CANVAS_W * s) / 2f, (size.height - CANVAS_H * s) / 2f) {
        scale(s, s, Offset.Zero) {
            // 누운 자세는 매트가 가운데 오도록 옮김
            val lying = motion.posture == NextyPosture.LIE || motion.posture == NextyPosture.SIDE
            translate(MARGIN_X + if (lying) LIE_SHIFT_X else 0f, if (lying) LIE_SHIFT_Y else 0f) {
                drawNexty(pose, motion.posture, prop, face, night, breath, eyeOpen, mouthOpen)
                if (zz) drawZz(breath)
            }
        }
    }
}

/**
 * 검사용: 동작의 자세들을 순서대로 (각 단계 자세와, 앞 단계에서 넘어가는 중간 자세 between개씩). 그림으로 동작을 확인할 때 씀
 */
internal fun nextyKeyPoses(motion: NextyMotion, between: Int = 1): List<NextyPose> {
    val out = mutableListOf<NextyPose>()
    var prev = NextyPose()
    motion.steps.forEach { step ->
        for (i in 1..between) out += lerp(prev, step.pose, i / (between + 1f))
        out += step.pose
        prev = step.pose
    }
    return out
}

/** 검사용: 동작의 단계 자세마다 표정·Zz (nextyKeyPoses와 같은 순서, 중간 자세는 다음 단계의 것) */
internal fun nextyKeyFaces(motion: NextyMotion, between: Int = 1): List<Pair<NextyFace, Boolean>> =
    motion.steps.flatMap { step -> List(between + 1) { (step.face ?: motion.face) to step.zz } }

/** 검사용: 동작의 단계 자세마다 소품 (nextyKeyPoses와 같은 순서) */
internal fun nextyKeyProps(motion: NextyMotion, between: Int = 1): List<NextyProp> =
    motion.steps.flatMap { step -> List(between + 1) { step.prop ?: motion.prop } }

/** 검사용: 한 자세를 그린 그림 (깜박임 없이, 숨쉬기는 breath 0~1) */
internal fun renderNextyPose(
    motion: NextyMotion, pose: NextyPose, width: Int, height: Int,
    face: NextyFace = motion.face, zz: Boolean = false, night: Boolean = false, prop: NextyProp = motion.prop,
    breath: Float = 0f
): androidx.compose.ui.graphics.ImageBitmap {
    val bitmap = androidx.compose.ui.graphics.ImageBitmap(width, height)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr,
        androidx.compose.ui.graphics.Canvas(bitmap), Size(width.toFloat(), height.toFloat())
    ) {
        drawRect(Color(0xFF2B2440))
        drawNextyFrame(motion, pose, face, night = night, breath = breath, eyeOpen = 1f, mouthOpen = 0f, zz = zz, prop = prop)
    }
    return bitmap
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
    if (posture == NextyPosture.ALL4 || posture == NextyPosture.KNEEL) {
        drawOnFloor(pose, posture, face, night, breath, eyeOpen, mouthOpen)
        return
    }
    val base = Offset(100f, 196f)      // 몸이 기울고 늘어나는 기준점 (엉덩이)
    val lying = posture == NextyPosture.LIE || posture == NextyPosture.SIDE

    when (posture) {
        NextyPosture.STAND -> if (prop == NextyProp.CALF_WALL) drawCalfWall(pose) else drawFeet(pose.lift, pose.lean)
        NextyPosture.SIT -> when {
            isBedLegs(prop) -> { drawBed(); drawBedLegsBehind(pose, prop) }   // 내린 다리는 몸 뒤, 무릎에 올린 다리는 몸 앞 (drawBedLegs)
            isSitLegs(prop) -> drawSitLegs(pose, prop)
            else -> drawCrossedLegs()
        }
        else -> drawMat()
    }
    drawBackgroundProp(prop, breath)

    // 누운 자세: 선 자세로 그린 것을 머리가 왼쪽으로 가게 눕힘. hip만큼 등 위쪽을 축으로 엉덩이를 듦.
    // 발꿈치를 들면(lift) 발은 그대로 두고 몸만 올림. lean이면 왼발을 축으로 몸 전체를 기울임
    rotate(pose.lean, LEAN_PIVOT) {
    translate(0f, -pose.lift) {
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
                        translate(0f, -pose.shrug) {
                            drawArmBehind(left = true, pose.leftShoulder, pose.leftElbow, pose.leftReach)
                            drawArmBehind(left = false, pose.rightShoulder, pose.rightElbow, pose.rightReach)
                        }
                    }
                    drawBody(belly = prop != NextyProp.BACK_VIEW)
                    // 고개를 돌려 얼굴이 옆으로 옮겨 가도 몸 밖으로 나가지 않게 몸 윤곽 안에만 그림. 뒷모습이면 얼굴 대신 등
                    if (prop == NextyProp.BACK_VIEW) drawBack(pose.act)
                    else clipPath(bodyPath()) { translate(pose.faceX, pose.faceY) { drawFace(face, eyeOpen, mouthOpen) } }
                    if (night) drawNightCap()
                }
                // 가슴 쪽으로 당기거나 천장으로 든 다리는 몸 앞에 다시 그림 (그 위에 다리를 잡은 손이 오도록 팔보다 먼저)
                if (lying) drawLyingLegs(pose, posture, front = true)
                scale(1f - breath * 0.012f, pose.stretch + breath * 0.016f, base) {
                    if (prop == NextyProp.ARM_CROSS) {
                        drawCrossArms()
                    } else if (prop == NextyProp.TWIST_HUG) {
                        drawChestArms()
                    } else if (isPoseArms(prop)) {
                        drawPoseArms(prop, pose.act, front = true)
                    } else if (!customArmsOf(prop) && !pose.forearmsBehind) {
                        translate(0f, -pose.shrug) {
                            drawForearmInFront(left = true, pose.leftShoulder, pose.leftElbow, pose.leftReach)
                            drawForearmInFront(left = false, pose.rightShoulder, pose.rightElbow, pose.rightReach)
                        }
                    }
                    drawHand(prop, pose)
                    drawCue(prop, breath)
                }
            }
        }
    }
    }
    }
    // 침대에 걸터앉은 동작: 무릎 위를 가로지르는 다리와 발끝을 잡는 손은 몸 앞에
    if (posture == NextyPosture.SIT && isBedLegs(prop)) drawBedLegs(pose, prop, breath)
}

private fun customArmsOf(prop: NextyProp) =
    prop == NextyProp.ARM_CIRCLE || prop == NextyProp.ARM_CROSS || prop == NextyProp.TWIST_HUG || isPoseArms(prop)

/** 양반다리로 앉아 한쪽 다리를 옆으로 펴는 동작 (drawSitLegs) */
private fun isSitLegs(prop: NextyProp) = prop == NextyProp.LEG_OUT_TOWEL || prop == NextyProp.LEG_ANKLE_CIRCLE

/** 침대 끝에 걸터앉아 발목을 반대쪽 무릎에 올리는 동작 (drawBed · drawBedLegs) */
private fun isBedLegs(prop: NextyProp) = prop == NextyProp.LEG_FIGURE_FOUR || prop == NextyProp.LEG_TOE_PULL

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

private fun DrawScope.drawBody(belly: Boolean = true) {
    val body = bodyPath()
    drawPath(body, BODY)
    if (belly) drawOval(BELLY, Offset(68f, 140f), Size(64f, 46f))
    drawPath(body, OUTLINE, style = Stroke(OUTLINE_W))
}

/** 뒷모습의 등: 가운데 등골 선과 양쪽 날개뼈 선. squeeze(0~1)만큼 날개뼈가 가운데로 모임 */
private fun DrawScope.drawBack(squeeze: Float) {
    val k = squeeze.coerceIn(0f, 1f)
    val line = OUTLINE.copy(alpha = 0.45f)
    drawLine(line, Offset(100f, 132f), Offset(100f, 176f), 2.4f, StrokeCap.Round)
    listOf(-1f, 1f).forEach { side ->
        val x = 100f + side * (24f - 9f * k)
        val blade = Path().apply {
            moveTo(x + side * 6f, 116f)
            quadraticTo(x - side * 4f, 128f, x + side * 2f, 146f)
        }
        drawPath(blade, line, style = Stroke(2.6f, cap = StrokeCap.Round))
    }
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
            NextyFace.WINCE, NextyFace.FIGHT -> {   // 찡그린 눈 > < (파이팅은 힘준 눈)
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
            NextyFace.FIGHT -> {   // 외치는 입: 크게 벌린 둥근 입과 혀
                drawRoundRect(OUTLINE, Offset(90f, 115f), Size(20f, 19f), CornerRadius(9f))
                drawOval(BLUSH, Offset(94f, 125f), Size(12f, 7f))
            }
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
private fun DrawScope.drawCrossArms(drop: Float = CROSS_ARM_DROP) {
    listOf(1f, -1f).forEach { side ->
        fun x(v: Float) = 100f + side * (v - 100f)
        val arm = Path().apply {
            moveTo(x(100f - SHOULDER_X), SHOULDER_Y + drop)
            quadraticTo(x(82f), 132f + drop, x(134f), 88f + drop)
        }
        drawPath(arm, OUTLINE, style = Stroke(ARM_W + OUTLINE_W * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(arm, BODY, style = Stroke(ARM_W, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 상체 비틀기: 짧은 두 팔을 어깨에서 둥글게 굽혀 가슴 앞에 모음 (동그란 두 손이 가운데에서 맞닿음) */
private fun DrawScope.drawChestArms() {
    listOf(1f, -1f).forEach { side ->
        fun x(v: Float) = 100f + side * (v - 100f)
        val start = Offset(x(100f - SHOULDER_X), 134f)
        val control = Offset(x(56f), 158f)
        val end = Offset(x(89f), 151f)
        val arm = Path().apply {
            moveTo(start.x, start.y)
            quadraticTo(control.x, control.y, end.x, end.y)
        }
        // 팔과 동그란 손의 윤곽선을 먼저 모두 그리고 그 위에 몸 색을 칠해 손목에 선이 생기지 않게 함
        drawPath(arm, OUTLINE, style = Stroke(ARM_W + OUTLINE_W * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(OUTLINE, 7.5f + OUTLINE_W, end)
        drawPath(arm, BODY, style = Stroke(ARM_W, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(BODY, 7.5f, end)
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
    if (prop == NextyProp.CROSS_PULL) {
        // 당기는 팔(위팔·어깨)을 먼저, 그 위에 가로지르는 팔(손이 당기는 쪽 어깨 위로 옴), 마지막으로 팔꿈치를 거는 손만 다시
        inFront(helpArm)
        inFront(doArm)
        listOf(Offset(168f, 112f) to Offset(178f, 104f), Offset(174f, 128f) to Offset(186f, 126f), Offset(168f, 144f) to Offset(178f, 150f))
            .forEach { (a, b) ->
                val p = if (flip) mirrorX(a) else a
                val q = if (flip) mirrorX(b) else b
                drawLine(OUTLINE.copy(alpha = amount), p, q, OUTLINE_W * 0.8f, StrokeCap.Round)
            }
        drawLimb(
            listOf(lerpOffset(helpArm[1], helpArm[2], 0.62f), helpArm[2]),
            listOf(lerpOffset(helpArm[1], helpArm[2], 0.55f), helpArm[2])
        )
        return
    }
    inFront(doArm)
    run {
        val up = prop == NextyProp.WRIST_UP
        fun doHand() = drawMitten(doArm[2], if (up) -90f else 90f, 30f * amount, palm = up, thumbRight = if (up) flip else !flip, k = 1.5f,
            details = up, armDegrees = angleOf(doArm[1], doArm[2]))   // 손등(뒤쪽 늘이기)은 선 없이 매끈하게
        // 앞쪽 늘이기: 돕는 팔 → 손바닥 → 그 위에 돕는 손 (손가락 끝만 잡은 것처럼).
        // 뒤쪽 늘이기: 손등 → 그 위에 돕는 팔과 손 (손등을 누르는 손이 위)
        if (up) { inFront(helpArm); doHand() } else { doHand(); inFront(helpArm) }
        drawCircle(OUTLINE, 7f + OUTLINE_W, helpArm[2])
        drawCircle(BODY, 7f, helpArm[2])
        // 돕는 손과 팔 사이 손목 선 지우기: 팔 쪽에서 손 가운데까지 몸 색을 이어 칠함
        drawLine(BODY, polar(helpArm[2], angleOf(helpArm[1], helpArm[2]), -14f), helpArm[2], ARM_W, StrokeCap.Butt)
        return
    }
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
    if (prop == NextyProp.FIGHT_FISTS) {     // 파이팅: 두 손 모두 짧고 둥근 주먹
        listOf(true, false).forEach { left ->
            val (_, m, e) = if (left) armPoints(true, pose.leftShoulder, pose.leftElbow, pose.leftReach)
            else armPoints(false, pose.rightShoulder, pose.rightElbow, pose.rightReach)
            drawMitten(e, angleOf(m, e), 12f, palm = true, thumbRight = true, k = 1.3f, details = false)   // 두 주먹 모두 엄지가 바깥쪽
        }
        return
    }
    if (prop == NextyProp.BACK_VIEW || prop == NextyProp.BLADE_FRONT) {   // 날개뼈 모으기: 위를 향한 두 손 (엄지는 머리 쪽)
        listOf(true, false).forEach { left ->
            val (_, m, e) = if (left) armPoints(true, pose.leftShoulder, pose.leftElbow, pose.leftReach)
            else armPoints(false, pose.rightShoulder, pose.rightElbow, pose.rightReach)
            if (pose.leftShoulder > 40f) drawMitten(e, angleOf(m, e), 15f, palm = prop == NextyProp.BLADE_FRONT, thumbRight = left, k = 1.2f, details = false)
        }
        return
    }
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
    val length = 20f * (0.62f + 0.38f * ((cos(t).toFloat() + 1f) / 2f))
    // 손을 둘러싼 동그란 화살표 (화면 위로 잘리지 않게 작게)
    val c = polar(end, dir, 11f)
    rotate(dir, c) {
        drawArc(CUE, 30f, 290f, false, Offset(c.x - 12f, c.y - 20f), Size(24f, 40f), style = Stroke(3f, cap = StrokeCap.Round))
        val tip = Offset(c.x + 12f * cos(Math.toRadians(320.0)).toFloat(), c.y + 20f * sin(Math.toRadians(320.0)).toFloat())
        drawArrowHead(tip, 230f)
    }
    drawMitten(end, handDir, length, palm = cos(t) >= 0.0, thumbRight = false, k = 1.15f, armDegrees = dir)   // 엄지는 왼쪽, 손목만 돎
}

/** 움직이는 방향을 알려 주는 화살표 (몸과 함께 눕고 기욺) */
private fun DrawScope.drawCue(prop: NextyProp, breath: Float) {
    val wobble = breath * 2f
    when (prop) {
        // 턱을 가슴 쪽으로: 얼굴 옆에서 아래로 내려가는 화살표
        NextyProp.CUE_CHIN -> drawCueArrow(Offset(176f, 88f + wobble), Offset(186f, 106f), Offset(174f, 126f + wobble), both = false)
        // 고개를 좌우로: 머리 위의 양쪽 화살표
        NextyProp.CUE_HEAD, NextyProp.TWIST_HUG -> drawCueArrow(Offset(58f, 44f), Offset(100f, 14f - wobble), Offset(142f, 44f), both = true)
        // 어깨를 위아래로: 양쪽 어깨 바깥의 위아래 화살표
        NextyProp.CUE_SHRUG -> listOf(false, true).forEach { right ->
            fun m(p: Offset) = if (right) mirrorX(p) else p
            drawCueArrow(m(Offset(24f, 134f)), m(Offset(20f - wobble, 112f)), m(Offset(24f, 90f)), both = true)
        }
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
        NextyProp.FIGHT_FISTS -> {   // 파이팅: 몸 둘레로 퍼지는 짧은 강조선 (숨에 맞춰 살짝 커짐)
            val c = Offset(100f, 118f)
            listOf(-150f, -120f, -60f, -30f, 0f, 180f, 25f, 155f).forEach { deg ->
                val r = Math.toRadians(deg.toDouble())
                val dir = Offset(cos(r).toFloat(), sin(r).toFloat())
                val inner = 92f + breath * 3f
                drawLine(SUN_RAY, c + dir * inner, c + dir * (inner + 14f), 4f, StrokeCap.Round)
            }
        }
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

/** 잠든 넥스티 머리 오른쪽 위의 Zz (숨에 맞춰 살짝 떠오름). 밤하늘에서도 보이게 밝은 색 */
private fun DrawScope.drawZz(breath: Float) {
    val rise = breath * 3f
    listOf(Triple(166f, 74f, 5f), Triple(182f, 56f, 7.5f)).forEach { (x, y, h) ->
        val c = Offset(x, y - rise)
        val z = Path().apply {
            moveTo(c.x - h, c.y - h)
            lineTo(c.x + h, c.y - h)
            lineTo(c.x - h, c.y + h)
            lineTo(c.x + h, c.y + h)
        }
        drawPath(z, ZZ_COLOR, style = Stroke(h * 0.45f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
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

/**
 * 선 자세의 두 발. lift만큼 뒤꿈치를 들면 발끝으로 서서 발이 짧아 보이고 살짝 올라감.
 * lean만큼 몸을 왼발을 축으로 기울이면 오른발은 몸과 함께 돌아 저절로 뜸 (왼발은 바닥에 그대로)
 */
private fun DrawScope.drawFeet(lift: Float = 0f, lean: Float = 0f) {
    val h = 18f - lift * 0.5f
    val top = 186f - lift * 0.4f
    fun foot(x: Float) {
        drawOval(BODY, Offset(x, top), Size(30f, h))
        drawOval(OUTLINE, Offset(x, top), Size(30f, h), style = Stroke(OUTLINE_W))
    }
    foot(62f)
    rotate(lean, LEAN_PIVOT) { foot(108f) }
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

/** 선 자세 좌표를 몸 기울기(tilt)만큼 엉덩이(base)를 축으로 돌림 (몸 밖에서 그리는 것을 몸 안의 팔에 맞출 때) */
private fun tilted(p: Offset, tilt: Float, base: Offset = Offset(100f, 196f)): Offset {
    val r = Math.toRadians(tilt.toDouble())
    val d = p - base
    return Offset(base.x + d.x * cos(r).toFloat() - d.y * sin(r).toFloat(), base.y + d.x * sin(r).toFloat() + d.y * cos(r).toFloat())
}

// ---------------- 앉아서 다리를 쓰는 동작 (LEG_*) ----------------

private const val SIT_LEG_W = 16f

/** 양반다리 (drawCrossedLegs와 같은 선). 세 점 엉덩이 · 무릎 · 발목 중 엉덩이 = 무릎이라 선 하나로 보임 */
private val SIT_REST_OTHER = listOf(Offset(52f, 188f), Offset(52f, 188f), Offset(122f, 206f))   // 왼 무릎 → 오른쪽 발
private val SIT_REST_WORK = listOf(Offset(148f, 188f), Offset(148f, 188f), Offset(78f, 206f))   // 오른 무릎 → 왼쪽 발

/** 오른다리로 하는 쪽 기준 목표 위치: (받치는 왼다리, 하는 오른다리). 엉덩이 쪽은 몸에 가려짐 */
private fun sitLegTargets(prop: NextyProp): Pair<List<Offset>, List<Offset>> = when (prop) {
    // 오른 다리를 옆으로 쭉 폄 (발끝은 위로)
    NextyProp.LEG_OUT_TOWEL ->
        listOf(Offset(82f, 188f), Offset(46f, 194f), Offset(108f, 208f)) to
            listOf(Offset(122f, 190f), Offset(162f, 196f), Offset(200f, 200f))
    // 오른 다리를 옆으로 곧게 펴서 살짝 듦 (엉덩이 · 무릎 · 발목이 한 줄)
    else ->
        listOf(Offset(82f, 188f), Offset(46f, 194f), Offset(108f, 208f)) to
            listOf(Offset(122f, 190f), Offset(160f, 186f), Offset(196f, 182f))
}

/**
 * 앉아서 다리를 쓰는 동작의 두 다리와 발. act 0이면 양반다리, 1이면 목표 자세, 음수면 좌우를 뒤집음 (왼다리로 함).
 * 양반다리는 좌우를 뒤집으면 두 선이 서로 바뀔 뿐이라 쉬는 자세에서 반대쪽으로 자연스럽게 넘어감
 */
private fun DrawScope.drawSitLegs(pose: NextyPose, prop: NextyProp) {
    val amount = abs(pose.act).coerceIn(0f, 1f)
    val flip = pose.act < 0f
    fun m(p: Offset) = if (flip) mirrorX(p) else p
    fun dir(degrees: Float) = if (flip) 180f - degrees else degrees
    val (otherT, workT) = sitLegTargets(prop)
    val other = otherT.indices.map { m(lerpOffset(SIT_REST_OTHER[it], otherT[it], amount)) }
    val work = workT.indices.map { m(lerpOffset(SIT_REST_WORK[it], workT[it], amount)) }
    drawLimb(other, other, SIT_LEG_W)
    drawLimb(work, work, SIT_LEG_W)
    if (amount < 0.6f) return
    val grow = (amount - 0.6f) / 0.4f   // 다리를 거의 옮긴 뒤에 발이 나타남
    val ankle = work[2]
    val legDegrees = angleOf(work[1], ankle)
    if (prop == NextyProp.LEG_ANKLE_CIRCLE) {
        // 발은 다리와 직각으로 서 있고(발끝이 위), 발목을 축으로 발끝만 작은 원을 그림: 좌우로 조금 기울고
        // 화면 쪽·반대쪽으로 갈 때 조금 짧아 보임. 다리 쪽으로는 꺾이지 않음
        val t = Math.toRadians(pose.wristTurn.toDouble())
        val side = if (flip) -1f else 1f
        val upright = legDegrees - 90f * side
        val c = polar(ankle, upright, 15f)
        rotate(upright, c) {
            drawArc(CUE, 30f, 290f, false, Offset(c.x - 13f, c.y - 22f), Size(26f, 44f), style = Stroke(3f, cap = StrokeCap.Round))
            val tip = Offset(c.x + 13f * cos(Math.toRadians(320.0)).toFloat(), c.y + 22f * sin(Math.toRadians(320.0)).toFloat())
            drawArrowHead(tip, 230f)
        }
        val length = 22f * (0.8f + 0.2f * cos(t).toFloat())
        drawFoot(ankle, upright + 20f * side * sin(t).toFloat(), length * grow, legDegrees)
        return
    }
    // 다리 펴기: 발끝이 위로, 몸 쪽으로 살짝
    val footDegrees = dir(262f)
    val footLength = 24f * grow
    drawFoot(ankle, footDegrees, footLength, legDegrees)
    if (prop == NextyProp.LEG_OUT_TOWEL) {
        // 하는 쪽 손이 수건 끝을 잡음 (손은 나중에 그려 수건 끝을 덮음). 손은 몸과 함께 기울어 있으므로 같은 기울기로 옮김
        val arm = if (flip) armPoints(true, pose.leftShoulder, pose.leftElbow, pose.leftReach)
        else armPoints(false, pose.rightShoulder, pose.rightElbow, pose.rightReach)
        drawTowel(tilted(arm.third, pose.tilt), polar(ankle, footDegrees, footLength + 2f), if (flip) -1f else 1f, grow)
    }
}

/**
 * 발 (발목에서 발끝 방향 degrees로 length만큼, 뒤꿈치는 발목 뒤로 조금). 다리와 이어지는 곳은 다리 굵기로 몸 색을 덧칠해
 * 선이 생기지 않게 함 (덧칠하는 끝은 발 안쪽에 머물도록 다리 굵기의 반보다 짧게)
 */
private fun DrawScope.drawFoot(ankle: Offset, degrees: Float, length: Float, legDegrees: Float) {
    if (length < 4f) return
    rotate(degrees, ankle) {
        val topLeft = Offset(ankle.x - 8f, ankle.y - 8.5f)
        val size = Size(length + 8f, 17f)
        drawRoundRect(
            OUTLINE, topLeft - Offset(OUTLINE_W, OUTLINE_W), Size(size.width + OUTLINE_W * 2f, size.height + OUTLINE_W * 2f),
            CornerRadius(8.5f + OUTLINE_W)
        )
        drawRoundRect(BODY, topLeft, size, CornerRadius(8.5f))
    }
    // 발 윤곽선이 정강이 속에 선으로 남지 않게, 발의 옆 윤곽선(발목에서 약 12.5)보다 길게 몸 색을 이어 칠함
    drawLine(BODY, polar(ankle, legDegrees, -16f), ankle, SIT_LEG_W, StrokeCap.Butt)
}

// ---------------- 침대에 걸터앉아 발목을 무릎에 올리는 동작 (4자 · 발끝 당기기) ----------------

/** 걸터앉은 침대의 앞면 (몸 아래, 다리 뒤) */
private fun DrawScope.drawBed() {
    drawRoundRect(MAT, Offset(-8f, 188f), Size(216f, 26f), CornerRadius(8f))
    drawRoundRect(MAT_EDGE, Offset(-8f, 188f), Size(216f, 26f), CornerRadius(8f), style = Stroke(3f))
}

private const val BED_KNEE_Y = 204f        // 침대 앞 끝에 걸친 무릎 (앞에서 보면 허벅지는 짧아져 몸에 가려짐)
private const val BED_FOOT_Y = 225f        // 발이 캔버스 아래(240) 안에 들어오도록

/** 다리 위치 (오른다리로 하는 쪽 기준, flip이면 좌우 반대). amount 0: 두 다리를 침대 앞으로 내림, 1: 오른 발목을 왼 무릎 위에 올림 */
private class BedLegs(pose: NextyPose, prop: NextyProp) {
    val amount = abs(pose.act).coerceIn(0f, 1f)
    val flip = pose.act < 0f
    val toePull = prop == NextyProp.LEG_TOE_PULL
    fun m(p: Offset) = if (flip) mirrorX(p) else p
    fun dir(degrees: Float) = if (flip) 180f - degrees else degrees

    // 받치는 왼다리: 몸 아래(몸에 가려짐)에서 바닥으로 곧게 내림
    val otherTop = m(Offset(78f, LEG_TOP_Y))
    val otherAnkle = m(Offset(76f, BED_FOOT_Y))

    // 하는 오른다리: 엉덩이 → 무릎 → 발목. 발끝 당기기는 발목을 받치는 다리의 무릎 꼭대기(몸 아래 끝)에 올려 정강이가 그 위를 가로지름
    // 4자 스트레칭도 발끝 당기기와 같은 다리 위치·길이 (허벅지가 정강이 아래로 비치지 않게 엉덩이를 조금 높게)
    val hip = m(Offset(124f, 184f))
    val knee = m(lerpOffset(Offset(122f, BED_KNEE_Y), Offset(146f, 192f), amount))
    val ankle = m(lerpOffset(Offset(124f, BED_FOOT_Y), Offset(84f, 190f), amount))
    // 내린 다리는 몸 아래에서 곧게, 벌린 무릎은 몸 가장자리에서 허벅지가 나옴
    val thighStart = lerpOffset(Offset(knee.x, LEG_TOP_Y), hip, amount)
    val raised = amount >= 0.5f
}

private const val LEG_TOP_Y = 180f        // 내린 다리가 시작하는 높이 (몸 아래쪽 안, 몸에 가려짐)

/** 몸 뒤의 다리: 받치는 다리와 하는 다리. 몸 아래·안쪽에서 나와 몸과 떨어져 보이지 않음 (무릎에 올린 정강이는 drawBedLegs가 몸 앞에 다시) */
private fun DrawScope.drawBedLegsBehind(pose: NextyPose, prop: NextyProp) {
    val l = BedLegs(pose, prop)
    drawLimb(listOf(l.otherTop, l.otherAnkle), listOf(l.otherTop, l.otherAnkle), SIT_LEG_W)
    drawHangingFoot(l.otherAnkle)
    // 하는 다리는 늘 몸 뒤에서 나옴 (허벅지가 몸 안쪽에서 시작해 윤곽선 밖으로 색이 나오거나 선이 끊기지 않게)
    drawLimb(listOf(l.thighStart, l.knee, l.ankle), listOf(l.thighStart, l.knee, l.ankle), SIT_LEG_W)
    if (!l.raised) drawHangingFoot(l.ankle)
}

/**
 * 침대 끝에 걸터앉아 오른 발목을 왼 무릎에 올린 다리 (몸 앞). act 음수면 좌우 반대.
 * 1이면 오른 무릎을 바깥으로 벌리고 오른 발목을 왼 무릎 위에 올려 정강이가 무릎 위를 가로지름 (4자).
 * 발끝 당기기(LEG_TOE_PULL)는 자세의 왼팔 끝 주먹이 발끝을 잡고, nearAnkle(0~1)만큼 발끝을 정강이 쪽(위)으로 당김
 */
private fun DrawScope.drawBedLegs(pose: NextyPose, prop: NextyProp, breath: Float = 0f) {
    val l = BedLegs(pose, prop)
    if (!l.raised) return
    val amount = l.amount
    val flip = l.flip
    val toePull = l.toePull
    val knee = l.knee
    val ankle = l.ankle
    fun dir(degrees: Float) = l.dir(degrees)
    // 무릎 위를 가로지르는 정강이만 몸 앞에 다시 그림. 윤곽선은 무릎 조금 지나서부터 (무릎에 둥근 이음새 선이 생기지 않게)
    drawLimb(listOf(lerpOffset(knee, ankle, 0.15f), ankle), listOf(knee, ankle), SIT_LEG_W)
    // 무릎 위의 발: 발끝이 바깥(왼쪽), 당기면 정강이 쪽(위)으로 들림
    // 반대쪽(mirrored)은 두 다리 값을 서로 바꾸므로 당긴 정도가 farAnkle로 옮겨 감
    val pull = if (toePull) maxOf(pose.nearAnkle, pose.farAnkle).coerceIn(0f, 1f) else 0f
    // 발끝 당기기: 쉴 때는 발끝이 바깥 위로 비스듬히, 당기면 발끝이 위(정강이 쪽)로 섬
    val footDegrees = dir(if (toePull) lerp(232f, 268f, pull) else 196f)
    val footLength = if (toePull) 28f else 24f
    drawFoot(ankle, footDegrees, footLength * (amount - 0.5f) / 0.5f, angleOf(knee, ankle))
    if (!toePull) return

    // 발가락을 잡는 주먹: 팔은 자세대로 그려져 있고(왼팔, 반대쪽이면 오른팔), 그 끝에 주먹을 발 위로 그림.
    // 팔은 몸과 같이 숨쉬기 크기로 그려지므로 주먹도 같은 크기로
    if (amount < 0.05f) return
    val (_, mid, hand) = if (flip) armPoints(false, pose.rightShoulder, pose.rightElbow, pose.rightReach)
    else armPoints(true, pose.leftShoulder, pose.leftElbow, pose.leftReach)
    scale(1f - breath * 0.012f, pose.stretch + breath * 0.016f, Offset(100f, 196f)) {
        drawMitten(hand, angleOf(mid, hand), 11f, palm = false, thumbRight = flip, k = 1.2f, details = false)   // 엄지는 몸 안쪽
    }
}

/** 바닥에 내린 발 (앞에서 본 납작한 발) */
private fun DrawScope.drawHangingFoot(ankle: Offset) {
    val topLeft = Offset(ankle.x - 14f, ankle.y - 4f)
    val size = Size(28f, 14f)
    drawOval(OUTLINE, topLeft - Offset(OUTLINE_W, OUTLINE_W), Size(size.width + OUTLINE_W * 2f, size.height + OUTLINE_W * 2f))
    drawOval(BODY, topLeft, size)
    drawLine(BODY, Offset(ankle.x, ankle.y - 10f), Offset(ankle.x, ankle.y + 2f), SIT_LEG_W, StrokeCap.Butt)
}

/** 손에서 발끝으로 걸친 수건 (발끝 위를 넘어 바깥쪽으로 조금 내려옴. side 1: 오른발, -1: 왼발) */
private fun DrawScope.drawTowel(hand: Offset, toe: Offset, side: Float, alpha: Float) {
    val path = Path().apply {
        moveTo(hand.x, hand.y)
        quadraticTo((hand.x + toe.x) / 2f, (hand.y + toe.y) / 2f + 5f, toe.x, toe.y)
        lineTo(toe.x + 6f * side, toe.y + 8f)
    }
    drawPath(path, OUTLINE.copy(alpha = alpha), style = Stroke(7f + OUTLINE_W, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, WATER.copy(alpha = alpha), style = Stroke(7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ---------------- 바닥 위 자세 (네발로 · 무릎 꿇고) ----------------

private const val FLOOR_SCALE = 0.85f      // 팔다리가 바닥에 닿도록 몸을 조금 작게
private const val FLOOR_Y = 206f           // 바닥 윗면
private const val FLOOR_CONTACT_Y = 197f   // 바닥에 닿은 손·무릎·발 선의 가운데 (굵기의 반만큼 위)
private val BODY_CENTER = Offset(100f, 124f)
// 네발 몸통: 무릎 꿇고 상체를 세운 몸처럼 길쭉해 보이도록 조금 길고 얇게 (다리가 보이면 같은 비율도 짧아 보임)
private const val ALL4_LENGTH = 1.12f
private const val ALL4_THICKNESS = 0.96f
private const val ALL4_BODY_Y = 136f      // 네발 몸 가운데 높이 (클수록 몸이 낮아져 바닥까지 보이는 팔다리가 짧아짐. 짧은 팔다리가 귀엽게)

/** 옆에서 본 바닥 (매트 단면) */
private fun DrawScope.drawFloor() {
    drawRoundRect(MAT, Offset(-22f, FLOOR_Y), Size(244f, 14f), CornerRadius(7f))
    drawRoundRect(MAT_EDGE, Offset(-22f, FLOOR_Y), Size(244f, 14f), CornerRadius(7f), style = Stroke(3f))
}

/**
 * 네발 자세(ALL4)와 아기 자세(KNEEL)를 옆에서 본 모습. 선 자세로 그린 몸을 몸 가운데를 축으로 돌려 머리가 왼쪽으로 가게 하고
 * (누운 자세처럼 몸의 왼쪽이 바닥 쪽), 팔다리는 몸 뒤에서 나와 바닥에 닿음. 먼 쪽 팔다리를 먼저, 조금 옆으로 옮겨 그림.
 * ALL4: 손은 어깨 아래, 무릎은 엉덩이 아래 바닥에 고정되고 arch에 따라 몸이 오르내리며 등이 둥글어지거나 펴짐 (tilt로 고개).
 * KNEEL: act 0이면 무릎 꿇고 상체를 세워 앉은 모습, 1이면 엉덩이를 뒤꿈치에 두고 상체를 숙여 두 팔을 앞으로 뻗은 모습
 */
private fun DrawScope.drawOnFloor(
    pose: NextyPose,
    posture: NextyPosture,
    face: NextyFace,
    night: Boolean,
    breath: Float,
    eyeOpen: Float,
    mouthOpen: Float
) {
    val kneel = posture == NextyPosture.KNEEL
    val fold = if (kneel) pose.act.coerceIn(0f, 1f) else 1f
    val angle = (if (kneel) -80f * fold else -90f) + pose.tilt
    val center = if (kneel) lerpOffset(Offset(112f, 132f), Offset(98f, 150f), fold) else Offset(98f, ALL4_BODY_Y - pose.arch * 6f)
    // 몸 모양은 바꾸지 않음 (등을 말고 펼 때는 몸 전체가 오르내리고 고개만 움직임). 네발 몸통은 아기 자세(숨 들이쉰 길이)와 같게 조금 길게
    val sx = FLOOR_SCALE * (if (kneel) 1f else ALL4_THICKNESS) * (1f - breath * 0.012f)    // 몸 앞뒤 두께 (네발이면 화면 위아래)
    val sy = FLOOR_SCALE * ((if (kneel) 1f else ALL4_LENGTH) * pose.stretch + breath * 0.016f)   // 머리 ~ 엉덩이 길이
    fun onScreen(p: Offset): Offset {
        val d = Offset((p.x - BODY_CENTER.x) * sx, (p.y - BODY_CENTER.y) * sy)
        val r = Math.toRadians(angle.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return center + Offset(d.x * c - d.y * s, d.x * s + d.y * c)
    }

    drawFloor()
    val shoulder: Offset
    val hip: Offset
    val hand: Offset
    val knee: Offset
    val foot: Offset
    if (kneel) {
        // 어깨: 상체를 세우면 얼굴(입·볼) 아래, 숙이면 머리 앞쪽 아래(얼굴 바로 뒤)로 옮겨 감 (몸 가운데에서 팔이 나와 빠져 보이지 않게)
        shoulder = onScreen(lerpOffset(Offset(64f, 142f), Offset(55f, 101f), fold))
        // 손: 숙이면 머리 조금 앞 바닥까지만 뻗음 (짧은 팔)
        hand = lerpOffset(Offset(52f, 178f), Offset(24f, FLOOR_CONTACT_Y), fold)
        // 무릎 꿇고 뒤꿈치에 앉은 다리: 엉덩이 → 앞쪽 바닥의 무릎(허벅지는 정강이 위에 거의 눕힘) → 바닥에 붙인 정강이 → 엉덩이 바로 밑의 발.
        // 숙이면 무릎은 가슴 아래로, 발은 그대로 엉덩이 밑에
        hip = lerpOffset(Offset(106f, 186f), Offset(144f, 184f), fold)   // 세우면 엉덩이를 뒤꿈치에 붙이고 앉음
        // 짧은 다리: 무릎과 발을 엉덩이 가까이
        knee = lerpOffset(Offset(64f, FLOOR_CONTACT_Y), Offset(114f, FLOOR_CONTACT_Y), fold)
        foot = lerpOffset(Offset(112f, FLOOR_CONTACT_Y + 2f), Offset(150f, FLOOR_CONTACT_Y + 2f), fold)
    } else {
        // 어깨는 머리 바로 아래가 아니라 몸 앞쪽 가운데쯤 (팔이 얼굴 밑에 붙지 않게), 손은 어깨 바로 아래 바닥
        shoulder = onScreen(Offset(60f, 110f))
        hip = onScreen(Offset(50f, 160f))   // 몸 아래쪽 가까이, 엉덩이 끝보다 안쪽 (다리 이음새가 몸 밖으로 어긋나 보이지 않게)
        hand = Offset(84f, FLOOR_CONTACT_Y)
        // 짧은 다리: 무릎은 엉덩이 바로 아래, 바닥에 붙인 정강이도 짧게
        knee = Offset(132f, FLOOR_CONTACT_Y)
        foot = Offset(146f, FLOOR_CONTACT_Y + 2f)
    }
    val nearLeg = listOf(hip, knee, foot)
    listOf(Offset(9f, -2f), Offset.Zero).forEach { o ->
        // 아기 자세의 바깥쪽(화면에 가까운) 다리는 몸에 가려지지 않게 몸 앞에 그림 (아래)
        if (!kneel) {
            // 바깥쪽 다리는 몸 앞에 따로 그림 (아래)
            if (o != Offset.Zero) {
                val leg = nearLeg.map { it + o }
                drawLimb(leg, leg, LEG_W)
            }
        } else if (o != Offset.Zero) {
            drawFoldedLeg(nearLeg.map { it + o })
        }
        // 바깥쪽(화면에 가까운) 팔은 몸 앞에 따로 그림 (아래)
        if (o != Offset.Zero) {
            val arm = listOf(shoulder + o, hand + o)
            drawLimb(arm, arm)
        }
    }

    // 몸통은 옆모습: 윤곽만 돌려 그리고, 얼굴과 꼭지는 몸과 함께 눕히지 않고 머리 앞쪽에 거의 바로 세워 붙임.
    // lean 0: 상체를 세움(얼굴은 몸 왼쪽 위) ~ 1: 몸이 가로(얼굴은 머리 끝 = 화면 왼쪽). 고개(tilt)는 얼굴도 따라 기욺
    val baseAngle = angle - pose.tilt
    val lean = (-baseAngle / 90f).coerceIn(0f, 1f)
    // 햇살 꼭지: 정수리(서 있을 때의 머리 꼭대기)에 붙여 몸과 같이 돌림 (엎드리면 머리가 향한 앞쪽으로 뻗음)
    if (!night) {
        val rayRoot = Offset(100f, 60f)
        val root = onScreen(rayRoot)
        translate(root.x - rayRoot.x, root.y - rayRoot.y) {
            rotate(angle, rayRoot) { scale(FLOOR_SCALE, FLOOR_SCALE, rayRoot) { drawSunRays() } }
        }
    }
    translate(center.x - BODY_CENTER.x, center.y - BODY_CENTER.y) {
        rotate(angle, BODY_CENTER) {
            scale(sx, sy, BODY_CENTER) {
                val body = bodyPath()
                drawPath(body, BODY)
                // 옆에서 본 배: 몸 앞쪽(서면 왼쪽, 엎드리면 바닥 쪽)
                clipPath(body) { drawOval(BELLY, Offset(34f, 104f), Size(44f, 84f)) }
                drawPath(body, OUTLINE, style = Stroke(OUTLINE_W))
            }
        }
    }
    // 아기 자세: 바깥쪽 다리는 몸 앞에 (몸에 가려지지 않게)
    if (kneel) drawFoldedLeg(nearLeg)
    // 얼굴: 상체를 세우면 몸 왼쪽(앞)에서 왼쪽을 봄. 엎드리면 얼굴이 머리 아래쪽에서 바닥을 봄
    // (눈은 정수리 쪽, 입은 그보다 가슴 쪽이고 둘 다 아래 윤곽선 바로 안쪽. 볼은 눈 뒤). 옆얼굴은 몸과 같이 돌림
    val faceShift = Offset(pose.faceX, pose.faceY)
    drawSideFace(
        eye = onScreen(lerpOffset(Offset(62f, 104f), Offset(72f, 68f), lean)) + faceShift,
        mouth = onScreen(lerpOffset(Offset(41f, 121f), Offset(52f, 82f), lean)) + faceShift,
        blush = onScreen(lerpOffset(Offset(72f, 120f), Offset(84f, 82f), lean)) + faceShift,
        turn = angle, face = face, eyeOpen = eyeOpen, mouthOpen = mouthOpen
    )
    // 바깥쪽 팔(네발·아기 자세)은 몸 앞에 통째로 그림 (얼굴보다 나중: 바닥을 보는 얼굴의 입은 바닥에 뻗은 팔에 가려짐). 팔 양옆 윤곽선이 몸통 안 어깨까지 이어지고 위끝은 열려 있음
    // (몸 색을 어깨 위로 조금 더 칠해 윤곽선 위끝의 둥근 선을 덮음)
    run {
        val up = polar(shoulder, angleOf(hand, shoulder), 8f)
        drawLimb(listOf(shoulder, hand), listOf(up, hand))
    }
    // 네발 자세의 바깥쪽 다리도 몸 앞에: 엉덩이 이음새(다리 양옆 윤곽선)가 몸통 안까지 보이고 위끝은 열려 있음
    if (!kneel) {
        val (h, k, f) = nearLeg
        drawLimb(listOf(h, k, f), listOf(polar(h, angleOf(k, h), 8f), h, k, f), LEG_W)
    }
}

/**
 * 무릎 꿇어 접은 다리 (엉덩이 · 무릎 · 발). 다리는 한 덩어리로 그리고, 허벅지 뒤쪽(엉덩이 쪽)의 아래 윤곽선만 그음.
 * 무릎이 접히는 안쪽에는 선을 긋지 않음
 */
private fun DrawScope.drawFoldedLeg(leg: List<Offset>) {
    val (hip, knee, _) = leg
    drawLimb(leg, leg, LEG_W)
    val along = hip - knee
    val len = along.getDistance()
    if (len < 1f) return
    // 허벅지에 수직이고 아래(정강이 쪽)를 향하는 방향으로 다리 굵기의 반만큼 옮긴 선
    var n = Offset(-along.y / len, along.x / len)
    if (n.y < 0f) n = -n
    val edge = n * (LEG_W / 2f)
    drawLine(OUTLINE, knee + along * 0.4f + edge, knee + along * 0.95f + edge, OUTLINE_W, StrokeCap.Round)
}

/**
 * 왼쪽을 보는 옆얼굴. 앞에서 본 얼굴을 돌린 것이 아니라 옆모습으로 그림: 좁은 눈 하나, 그 뒤 아래의 작은 볼,
 * 머리 앞쪽 윤곽선에 붙은 입. eye·mouth·blush는 화면 위치, turn은 얼굴의 기울기 (0이면 똑바로, 얼굴이 왼쪽을 봄).
 * 바닥 위 자세의 몸 크기(FLOOR_SCALE)에 맞춰 줄여 그림
 */
private fun DrawScope.drawSideFace(
    eye: Offset, mouth: Offset, blush: Offset, turn: Float, face: NextyFace, eyeOpen: Float, mouthOpen: Float
) {
    val k = FLOOR_SCALE
    rotate(turn, blush) { drawOval(BLUSH, Offset(blush.x - 7f * k, blush.y - 4.5f * k), Size(14f * k, 9f * k)) }
    // 눈: 옆에서 보면 폭이 좁음
    rotate(turn, eye) {
        val x = eye.x
        val y = eye.y
        when (face) {
            NextyFace.NORMAL -> {
                val h = 12f * k * eyeOpen.coerceIn(0f, 1f)
                if (h < 1.5f) {
                    drawLine(OUTLINE, Offset(x - 3.5f * k, y), Offset(x + 3.5f * k, y), OUTLINE_W, StrokeCap.Round)
                } else {
                    drawOval(OUTLINE, Offset(x - 3.5f * k, y - h / 2f), Size(7f * k, h))
                    if (eyeOpen > 0.6f) drawCircle(Color.White, 1.6f * k, Offset(x - 1f * k, y - 3f * k))
                }
            }
            NextyFace.HAPPY, NextyFace.GRIN -> drawArc(     // 웃는 눈 ∩ (좁게)
                OUTLINE, 200f, 140f, false, Offset(x - 5f * k, y - 5f * k), Size(10f * k, 10f * k), style = Stroke(OUTLINE_W, cap = StrokeCap.Round)
            )
            NextyFace.FOCUS, NextyFace.SLEEPY -> {           // 감은 눈 ∪ (좁게) + 앞쪽 끝의 짧은 속눈썹
                drawArc(OUTLINE, 20f, 140f, false, Offset(x - 5f * k, y - 5f * k), Size(10f * k, 8f * k), style = Stroke(OUTLINE_W, cap = StrokeCap.Round))
                drawLine(OUTLINE, Offset(x - 4.5f * k, y + 0.5f * k), Offset(x - 7.5f * k, y - 1.5f * k), OUTLINE_W * 0.8f, StrokeCap.Round)
            }
            NextyFace.WINCE, NextyFace.FIGHT -> {           // > (옆에서 본 찡그린 눈 하나)
                val e = Path().apply { moveTo(x + 3f * k, y - 5f * k); lineTo(x - 3f * k, y); lineTo(x + 3f * k, y + 5f * k) }
                drawPath(e, OUTLINE, style = Stroke(OUTLINE_W, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
    // 입: 머리 앞쪽 윤곽선 바로 안쪽의 작은 입 (옆에서 본 미소, 말할 때는 작게 벌림)
    rotate(turn, mouth) {
        if (mouthOpen > 0f) {
            drawOval(OUTLINE, Offset(mouth.x - 1f * k, mouth.y - 2f * k), Size(6f * k, (3f + mouthOpen * 4f) * k))
        } else {
            drawArc(OUTLINE, 20f, 110f, false, Offset(mouth.x - 1f * k, mouth.y - 4f * k), Size(9f * k, 7f * k), style = Stroke(OUTLINE_W, cap = StrokeCap.Round))
        }
    }
}
