package com.wuyi.guard

/**
 * ============================================================================
 *  无翼守护 —— 集中配置
 * ----------------------------------------------------------------------------
 *  ★★★ 后续所有需要修改的内容，基本都在这个文件里改，改完重新编译即可。★★★
 *
 *  文件位置：
 *      app/src/main/java/com/wuyi/guard/AppConfig.kt
 * ============================================================================
 */
object AppConfig {

    /* ======================================================================
     *  一、公告弹窗（进主界面时弹出）
     * ==================================================================== */

    /** 公告标题 */
    const val ANNOUNCEMENT_TITLE: String = "公告"

    /**
     * 公告正文 —— 【公告内容就改这里】
     * 用三引号包起来，中间随便换行，不需要写 \n。
     */
    val ANNOUNCEMENT_BODY: String = """
        欢迎使用无翼守护。
        不保证100%有效，就当上了个保险吧。
        第一次使用在主页下方测试功能是否正常
        测试务必使用有翼光崽！！！
        
        在“最近任务”给工具上锁可以避免重复授权。
        如果你的设备不能自动清理,在更多-备用清理按钮
        打开以上开关尝试。
        
        本软件不联网，也不读取、修改游戏数据，完全免费。
        如果你是在别处购买的，退款并举报。
        更新&交流&Bug反馈 QQ频道：pd50259977
        
    """.trimIndent()

    /** 公告版本号：内容每次更新后 +1，用户端就会再弹一次 */
    const val ANNOUNCEMENT_VERSION: Int = 1

    /** 弹窗底部按钮文字 */
    const val ANNOUNCEMENT_BUTTON_TEXT: String = "关闭"

    /** 是否每次进入都弹（true = 忽略版本号，每次都弹） */
    const val ANNOUNCEMENT_ALWAYS_SHOW: Boolean = true


    /* ======================================================================
     *  二、条件一：拿翼保护（OCR 文字比对）
     * ==================================================================== */

    /**
     * 拿翼特征文字 —— 【识别什么字触发，改这里】
     * 规则：屏幕上识别出的文字里，只要「包含」下面任意一个词，就算命中。
     * 想加词就往 listOf 里加，用英文逗号分隔，例如：
     *      listOf("更高更远", "光之翼", "获取")
     */
    val NA_YI_KEYWORDS: List<String> = listOf(
        "更高更远",
        "点击按钮获取",
        "翼之光",
        "可以让您",
        "增强你的翅膀",
        "飞得更远",
        "光之翼",// ← 照格式继续加
        // "自定义",
    )

    /** 是否忽略大小写（对中文无影响，主要针对英文关键词） */
    const val NA_YI_IGNORE_CASE: Boolean = true

    /** 是否把关键词当正则使用（true 时 NA_YI_KEYWORDS 里可以写正则，如 "拿.{0,2}翼"） */
    const val NA_YI_USE_REGEX: Boolean = false

    /** 需要连续命中的帧数才判定触发（1 = 一帧命中就触发；设 2~3 可防误判） */
    const val NA_YI_HIT_FRAMES: Int = 1

    /** 命中后是否忽略繁体/空格差异（会去掉所有空白再比对） */
    const val NA_YI_TRIM_SPACE: Boolean = true

    /** 关键词分隔符：英文逗号、中文逗号、英文分号、中文分号，任意一个都能分隔 */
    const val KEYWORD_SEPARATORS: String = ",，;；"


    /* ======================================================================
     *  三、条件二：过图保护（全屏变黑 / 变白）
     * ==================================================================== */

    /** 单帧平均亮度低于此值判定为「黑屏」（0~255） */
    const val MONO_BLACK_THRESHOLD: Int = 10

    /** 单帧平均亮度高于此值判定为「白屏」（0~255） */
    const val MONO_WHITE_THRESHOLD: Int = 240

    /** 黑/白状态持续超过这个毫秒数才触发（需求：1 秒） */
    const val MONO_DURATION_MS: Long = 400L

    /** 像素采样步长（越大越快，8 表示每 8 个像素取 1 个） */
    const val MONO_SAMPLE_STEP: Int = 8

    /**
     * 灵敏度档位（在「更多 → 规则设置 → 黑白屏灵敏度调节」里切换）
     *   0 = 默认（用上面这组当前值）
     *   1 = 灵敏（阈值更宽松、持续时间更短，更容易触发）
     */
    const val MONO_SENSITIVITY_DEFAULT: Int = 0
    const val MONO_SENSITIVITY_SENSITIVE: Int = 1

    /** 「灵敏」档的参数（更容易判定为黑/白，且更快触发） */
    const val MONO_BLACK_THRESHOLD_SENSITIVE: Int = 20
    const val MONO_WHITE_THRESHOLD_SENSITIVE: Int = 230
    const val MONO_DURATION_MS_SENSITIVE: Long = 250L


    /* ======================================================================
     *  四、OCR / 截图 帧率与性能
     * ==================================================================== */

    /** 目标帧率：每秒 xx 帧 */
    const val TARGET_FPS: Int = 5

    /**
     * 截图缩放比例（相对屏幕宽）。
     * 1.0 = 原图；0.5 = 一半。越小越快，但小字可能识别不到。
     * 拿翼提示字一般较大，建议 0.5~0.75。
     */
    const val CAPTURE_SCALE: Float = 0.6f

    /** OCR 是否允许跳帧：上一帧没识别完就丢弃新帧（保证不卡死，实际帧率会低于 TARGET_FPS） */
    const val OCR_DROP_BUSY_FRAME: Boolean = true

    /**
     * 无障碍截图通道下，送给 OCR 的图缩放比例（1.0 = 原图）。
     * 全屏原图（1080x2400 约 10MB）在每秒 10 帧下内存压力很大，容易 OOM，
     * 缩到 0.6 能省约 64% 内存，对识别率影响很小。字特别小时可调回 0.8~1.0。
     */
    const val A11Y_SHOT_OCR_SCALE: Float = 0.6f

    /**
     * 无障碍截图通道的截图间隔（毫秒）。0 = 跟随 TARGET_FPS。
     * 无障碍截图比录屏取帧慢，机器吃力时可设成 200（5fps）。
     */
    const val A11Y_SHOT_INTERVAL_MS: Long = 0L

    /**
     * Android 14（API 34）及以上：优先用「无障碍截图」取画面。
     *
     * 好处（解决录屏抢占）：
     *   - 不走 MediaProjection 通道，**系统录屏/第三方录屏不会把识屏挤掉**
     *   - 不需要每次弹授权框，重启后也不用重新授权
     * 代价：只支持 Android 14+（回调签名在 14 变了，低版本只能继续用 MediaProjection）。
     *
     * 若你ROM 不支持无障碍截图（日志会提示"连续失败"），把这里改成 false，
     * 就会统一回到 MediaProjection（需要授权，且会被录屏抢占）。
     */
    const val PREFER_ACCESSIBILITY_CAPTURE: Boolean = true

    /** 是否开启「无障碍节点取词」作为 OCR 的补充/降级（无 GMS 时唯一可用） */
    const val USE_NODE_TEXT_FALLBACK: Boolean = true

    /**
     * 主引擎「连续调用失败」多少次才降级到节点取词。
     * 注意：只看调用是否报错，**识别结果为空不算失败**
     * （游戏画面本来就可能没有可识别文字，属于正常）。
     */
    const val OCR_FAIL_TO_FALLBACK: Int = 5

    /**
     * 降级到节点取词后，每隔多少毫秒重新探测一次主引擎（0 = 不重试，永不切回）。
     * 作用：万一误判降级，能自动恢复。默认 30 秒。
     */
    const val OCR_FALLBACK_RETRY_MS: Long = 10_000L


    /* ======================================================================
     *  五、触发后执行的任务
     * ==================================================================== */

    /** 触发后是否回到系统主屏幕 */
    const val TASK_GO_HOME: Boolean = true

    /** 触发后是否清理后台（打开最近任务并点「清除全部」） */
    const val TASK_CLEAR_RECENT: Boolean = true

    /** 回到主屏前的延时（毫秒）。刚退出游戏时系统较忙，太小会导致后续动作被吞掉 */
    const val TASK_DELAY_BEFORE_HOME_MS: Long = 800L

    /** 打开最近任务后，第一次尝试找清除按钮前的等待（毫秒） */
    const val TASK_WAIT_RECENTS_MS: Long = 400L

    /**
     * 找「清除全部」按钮的最大尝试次数。
     * 每 TASK_CLEAR_POLL_INTERVAL_MS 找一次，找到就点。
     * 最近任务界面打开慢的机器也能等到（默认 5 次 × 300ms ≈ 1.5 秒）。
     */
    const val TASK_CLEAR_MAX_ATTEMPTS: Int = 5

    /** 每次尝试找清除按钮的间隔（毫秒） */
    const val TASK_CLEAR_POLL_INTERVAL_MS: Long = 300L

    /** 打开最近任务失败后，隔多久重试一次（毫秒） */
    const val TASK_RECENTS_RETRY_MS: Long = 400L

    /** 点击清除按钮后的等待时间（毫秒） */
    const val TASK_AFTER_CLEAR_MS: Long = 400L

    /** 单次任务的超时保护（毫秒）：超过这个时间强制解锁，避免卡住后所有后续任务都被拒 */
    const val TASK_TIMEOUT_MS: Long = 6_000L

    /** 触发后冷却时间（毫秒）：这段时间内不再重复触发 */
    const val TRIGGER_COOLDOWN_MS: Long = 2000L

    /**
     * 【兜底】找不到「清除全部」按钮时，是否改用坐标点击。
     *
     * 适用：清理按钮是纯图标、既没有文字也没有内容描述的 ROM
     *      （例如部分 FuntouchOS 的 X 图标、鸿蒙的垃圾桶图标）。
     *
     * 默认 **false**。开启前请先点「导出当前窗口节点树」确认按钮的真实位置，
     * 再把下面的百分比调到该位置 —— 否则可能点到别的东西。
     */
    const val CLEAR_FALLBACK_TAP: Boolean = false

    /** 兜底点击的横向位置（屏幕百分比）。多数 ROM 的清除键在底部中央 */
    const val CLEAR_FALLBACK_X_PERCENT: Float = 0.5f

    /** 纵向位置：全面屏手势（导航栏模式「关闭」时用这个） */
    const val CLEAR_FALLBACK_Y_GESTURE: Float = 0.92f

    /** 纵向位置：三键 / 导航栏（导航栏模式「开启」时用这个，位置要往上挪一点） */
    const val CLEAR_FALLBACK_Y_NAVBAR: Float = 0.88f

    /** 导航栏模式默认值：false = 全面屏手势 */
    const val DEFAULT_NAV_BAR_MODE: Boolean = false

    /** 日志窗口高度 = 屏幕高度 ÷ 这个值（默认 6，即 1/6 屏；想更小改 8） */
    const val MORE_LOG_WINDOW_DIVISOR: Int = 6

    /** 节点树导出时最多 dump 多少个节点（防止日志过大） */
    const val DUMP_MAX_NODES: Int = 400

    /**
     * 最近任务界面「清除全部」按钮的文字（各厂商叫法不同）
     * 只要文本或内容描述里包含任意一个，就会点击它。
     */
    val CLEAR_ALL_KEYWORDS: List<String> = listOf(
        "清除全部", "全部清除", "清理全部", "全部清理", "清除", "清理",
        "一键清理", "关闭全部", "全部关闭", "结束全部",
        "clear all", "close all", "dismiss all"
    )


    /* ======================================================================
     *  六、日志
     * ==================================================================== */

    /** 是否把日志写入文件（txt） */
    const val LOG_TO_FILE: Boolean = true

    /** 是否同时输出到 logcat */
    const val LOG_TO_LOGCAT: Boolean = false

    /** 日志文件保留天数 */
    const val LOG_KEEP_DAYS: Int = 1

    /** 单条触发事件是否单独记录到 trigger.txt */
    const val LOG_TRIGGER_SEPARATE: Boolean = true


    /* ======================================================================
     *  七、悬浮窗
     * ==================================================================== */

    /** 悬浮窗初始位置（距屏幕左边 / 上边的像素） */
    const val FLOAT_DEFAULT_X: Int = 24
    const val FLOAT_DEFAULT_Y: Int = 240

    /** 悬浮窗初始透明度（0.0 ~ 1.0） */
    const val FLOAT_ALPHA: Float = 0.92f

    /**
     * 折叠小图标的两档尺寸（dp），在「更多」页实时切换：
     *   FLOAT_COLLAPSED_SIZE_DP          = 标准档（默认）
     *   FLOAT_COLLAPSED_SIZE_DP_COMPACT  = 紧凑档（更省地方）
     * 这里的数值只作为初始值，实际值存在手机里（以手机上的为准）。
     */
    const val FLOAT_COLLAPSED_SIZE_DP: Int = 56
    const val FLOAT_COLLAPSED_SIZE_DP_COMPACT: Int = 48

    /** 默认用哪一档：false = 标准档 56dp，true = 紧凑档 48dp */
    const val DEFAULT_COLLAPSED_COMPACT: Boolean = false

    /** 悬浮窗是否限制在屏幕内（横竖屏切换 / 分辨率变化时自动拉回，防止跑出屏幕） */
    const val FLOAT_CLAMP_TO_SCREEN: Boolean = true

    /**
     * 【默认关闭】是否启用「点击悬浮窗以外的区域 → 自动缩小成小图标」。
     *
     * 原理上这只是窗口的触摸开关，与截图/OCR 无关；但实测在部分机型/游戏上，
     * 开启后会出现「游戏内文字识别不到、系统窗口正常」的现象（时间相关性明确），
     * 因此默认关闭以保住识别能力。
     *
     * - false（默认）：不自动缩小。想缩小就点悬浮窗标题栏的「—」按钮，
     *   或把下面的 FLOAT_IDLE_COLLAPSE_MS 设成一个毫秒数让它超时自动缩。
     * - true：改回点击外部自动缩小。如果又出现游戏内识别不到，请改回 false。
     */
    const val FLOAT_AUTO_COLLAPSE: Boolean = true

    /**
     * 展开后「多少毫秒没有操作」就自动缩小（0 = 不启用）。
     * 这个是纯定时器实现，不碰任何窗口触摸开关，不会干扰识别。
     * 建议值：3000（3 秒）。操作开关、拖动会重新计时。
     */
    const val FLOAT_IDLE_COLLAPSE_MS: Long = 0L

    /** 拿翼保护开关是否默认打开 */
    const val DEFAULT_NA_YI_ENABLED: Boolean = false

    /** 过图保护开关是否默认打开 */
    const val DEFAULT_GUO_TU_ENABLED: Boolean = false
}


/* ============================================================================
 *  八、日志文案（想把日志写成什么样，改这里）
 * ----------------------------------------------------------------------------
 *  写法：花括号 {xxx} 是占位符，运行时会自动替换成实际内容。
 *  例如把「命中特征文字」改成「触发保护」：
 *      const val NA_YI_HIT_FRAME = "发现目标 {kw}，第 {hit}/{need} 帧"
 *
 *  ★ 占位符可以随便删、随便挪位置，但名字要对得上（照抄下面的注释即可）
 *  ★ 占位符必须放在一对英文花括号里：{kw}  √    ｛kw｝ ×
 * ========================================================================== */
object LogText {

    /** 把模板里的 {xxx} 换成实际值，不用管这个函数 */
    fun fmt(template: String, vararg pairs: Pair<String, Any?>): String {
        var s = template
        for ((k, v) in pairs) {
            s = s.replace("{$k}", v?.toString() ?: "")
        }
        return s
    }

    /* ---------- 条件一：拿翼保护 ---------- */

    /** 每一帧命中特征文字时打印
     *  {kw}=命中的词  {hit}=第几帧  {need}=需要几帧才触发  {text}=屏幕识别到的原文（前60字） */
    const val NA_YI_HIT_FRAME: String =
        "命中特征文字「{kw}」（第 {hit}/{need} 帧）原文片段：{text}"

    /** 条件一真正触发时的详情（会写进 trigger_日期.txt）
     *  {kw}=命中的词 */
    const val NA_YI_TRIGGER_DETAIL: String =
        "识别到特征文字「{kw}」"

    /* ---------- 条件二：过图保护 ---------- */

    /** 刚开始检测到黑/白屏
     *  {kind}=黑屏/白屏  {luma}=当前亮度 */
    const val MONO_START: String = "检测到{kind}（亮度 {luma}），开始计时"

    /** 黑白状态被打断，计时清零
     *  {kind}=黑屏/白屏  {luma}=当前亮度 */
    const val MONO_BREAK: String = "{kind}中断，计时重置（亮度 {luma}）"

    /** 条件二真正触发时的详情
     *  {kind}=黑屏/白屏  {dur}=持续了多少毫秒  {luma}=亮度 */
    const val MONO_TRIGGER_DETAIL: String = "{kind}持续 {dur}ms（亮度 {luma}）"

    /* ---------- 触发记录（trigger_日期.txt 的整行格式） ---------- */

    /** {time}=时间  {reason}=拿翼保护/过图保护  {detail}=上面那条详情 */
    const val TRIGGER_LINE: String = "[{time}] 触发 -> {reason} | {detail}"

    /* ---------- 触发后执行的动作 ---------- */

    /** 任务开始
     *  {reason}=触发原因  {detail}=详情 */
    const val TASK_START: String = "==> 开始执行任务 | 原因：{reason} | 详情：{detail}"

    /** 回主屏幕结果
     *  {result}=成功/失败 */
    const val TASK_GO_HOME: String = "回系统主屏幕：{result}"

    /** 清理后台结果
     *  {result}=已点击清除按钮 / 未找到清除按钮 */
    const val TASK_CLEAR_RESULT: String = "清理后台：{result}"

    /** 任务结束 */
    const val TASK_END: String = "<== 任务结束"

    /** 冷却中，本次不触发
     *  {reason}=触发原因  {left}=还剩多少毫秒 */
    const val COOLDOWN_SKIP: String = "{reason} 命中但在冷却中，剩余 {left}ms 忽略"

    /* ---------- 运行状态 ---------- */

    /** 每 5 秒打印一次真实帧率
     *  {fps}=实际帧率  {target}=目标帧率  {luma}=当前亮度 */
    const val FRAME_STAT: String = "实际帧率 {fps} fps（目标 {target}），当前亮度 {luma}"

    /* ---------- 下面几个是"成功/失败"这类结果词，单独提出来方便改 ---------- */
    const val RESULT_OK: String = "成功"
    const val RESULT_FAIL: String = "失败"
    const val CLEAR_OK: String = "已点击清除按钮"
    const val CLEAR_FAIL: String = "未找到清除按钮"
    const val STATE_ON: String = "开启"
    const val STATE_OFF: String = "关闭"

    /* ========== 下面是"排查用"的内部日志 ========== */

    /* 应用启动 / 广播 */
    const val APP_START: String = "===== 无翼守护启动 ====="
    const val BOOT_RECEIVED: String = "收到开机广播"
    const val BOOT_RESTORE: String = "权限齐全，尝试恢复悬浮窗"

    /* 公告 */
    const val ANNOUNCEMENT_CLOSED: String = "公告已关闭，进入主界面"
    const val ANNOUNCEMENT_CLICK_CLOSE: String = "点击关闭公告"

    /* 无障碍服务 */
    const val A11Y_CONNECTED: String = "无障碍服务已连接"
    const val A11Y_INTERRUPTED: String = "无障碍服务被中断"
    const val A11Y_DESTROYED: String = "无障碍服务已销毁"
    const val A11Y_UNBOUND: String = "无障碍服务已解绑"
    const val A11Y_NOT_CONNECTED: String = "无障碍服务未连接，无法清理后台"
    const val A11Y_NODE_TEXT_ERROR: String = "节点取词异常"
    const val A11Y_CLEAR_START: String = "开始清理后台：打开最近任务"
    const val A11Y_RECENTS_RETRY: String = "第 1 次打开最近任务失败，{retry}ms 后重试"
    const val A11Y_RECENTS_RETRY_OK: String = "重试打开最近任务成功"
    const val A11Y_RECENTS_FAIL: String = "重试后仍打不开最近任务，跳过清理步骤"
    const val A11Y_CLEAR_HIT: String = "第 {n} 次尝试命中清除按钮"
    const val A11Y_CLEAR_FIND_ERROR: String = "查找清除按钮异常"
    const val A11Y_CLEAR_GIVE_UP: String =
        "已尝试 {n} 次仍未找到清除按钮，该 ROM 可能不支持"
    const val A11Y_SHOT_EMPTY: String = "截图结果为空"
    const val A11Y_SHOT_CONVERT_FAIL: String = "转换无障碍截图失败"
    const val A11Y_SHOT_FAIL: String = "无障碍截图失败，错误码 {code}"
    const val A11Y_SHOT_EXCEPTION: String = "无障碍截图调用异常"

    /* 屏幕采集 */
    const val CAPTURE_SERVICE_CREATED: String = "服务创建，OCR 引擎：{engine}"
    const val CAPTURE_SWITCH_STATE: String = "初始开关状态：拿翼={naYi} 过图={guoTu}"
    const val CAPTURE_STOP_CMD: String = "收到停止指令"
    const val CAPTURE_NOTIFY_FAIL: String = "构建通知失败"
    const val CAPTURE_NO_AUTH: String = "缺少授权数据，停止服务"
    const val CAPTURE_FG_FAIL: String = "startForeground 失败"
    const val CAPTURE_A11Y_START: String =
        "启动「无障碍截图」通道：目标 {fps}fps（不受录屏影响）"
    const val CAPTURE_A11Y_SHOT_FAIL: String =
        "无障碍截图连续失败 {n} 次：该 ROM 可能不支持"
    const val CAPTURE_PROCESS_SHOT_FAIL: String = "处理无障碍截图异常"
    const val CAPTURE_COPY_FAIL: String = "复制截图失败"
    const val CAPTURE_OCR_FRAME_FAIL: String = "本帧 OCR 引擎调用失败"
    const val CAPTURE_OCR_FRAME_FAIL2: String = "本帧 OCR 引擎调用失败（已降级）"
    const val CAPTURE_CREATE_PROJECTION: String = "开始创建 MediaProjection…"
    const val CAPTURE_CREATE_PROJECTION_FAIL: String = "创建 MediaProjection 失败"
    const val CAPTURE_PROJECTION_NULL: String = "MediaProjection 为空，停止服务"
    const val CAPTURE_CALLBACK_OK: String = "MediaProjection 回调已注册"
    const val CAPTURE_SIZE: String = "采集尺寸 {w}x{h} dpi={dpi}"
    const val CAPTURE_IMAGEREADER_FAIL: String = "创建 ImageReader 失败"
    const val CAPTURE_VIRTUALDISPLAY_FAIL: String = "创建 VirtualDisplay 失败"
    const val CAPTURE_STARTED: String = "开始采集 {w}x{h} @目标{fps}fps，引擎 {engine}"
    const val CAPTURE_PROJECTION_STOPPED: String =
        "MediaProjection 被系统停止：多半是系统录屏/第三方录屏抢占了通道"
    const val CAPTURE_FRAME_ERROR: String = "处理帧异常"
    const val CAPTURE_ALL_BLACK: String =
        "连续 30 帧全黑：目标应用可能禁止了截屏/录屏（FLAG_SECURE），MediaProjection 只能拿到黑屏，OCR 无法识别其中文字属正常现象"
    const val CAPTURE_IMG_TO_BMP_FAIL: String = "Image 转 Bitmap 失败"
    const val CAPTURE_STOPPED: String = "采集服务已停止"

    /* 悬浮窗 */
    const val FLOAT_SHOWN: String = "悬浮窗已显示"
    const val FLOAT_REMOVED: String = "悬浮窗已移除"
    const val FLOAT_ADD_FAIL: String = "添加悬浮窗失败（可能未授权悬浮窗权限）"
    const val FLOAT_NOTIFY_FAIL: String = "构建通知失败"
    const val FLOAT_FG_FAIL: String = "startForeground 失败（后台启动限制？）"
    const val FLOAT_FG_FALLBACK_FAIL: String = "startForeground 兜底也失败"
    const val FLOAT_CLICK_COLLAPSE: String = "点击缩小按钮"
    const val FLOAT_LONG_PRESS_CLOSE: String = "长按小图标，关闭悬浮窗"
    const val FLOAT_COLLAPSED: String = "悬浮窗折叠成图标"
    const val FLOAT_EXPANDED: String = "悬浮窗展开"
    const val FLOAT_NEED_MONITOR: String = "屏幕采集未启动，请先在 App 主界面点「开启屏幕监测」"

    /* 主界面 / 更多页 */
    const val HOME_CAPTURE_GRANTED: String = "屏幕采集授权成功，开始监测"
    const val HOME_A11Y_CHANNEL: String = "使用无障碍截图通道启动监测，无需录屏授权"
    const val HOME_CAPTURE_DENIED: String = "用户拒绝了屏幕采集授权"
    const val HOME_CAPTURE_NOT_RUNNING: String = "授权后采集仍未运行 —— 启动失败，请看运行日志"
    const val HOME_LAUNCH_FAIL: String = "启动屏幕采集授权失败"
    const val MORE_EXPORT_OK: String = "导出日志 {count} 个文件"
    const val MORE_EXPORT_FAIL: String = "导出日志失败"

    /* 权限跳转 */
    const val PERM_A11Y_FAIL: String = "打开无障碍设置失败"
    const val PERM_OVERLAY_FAIL: String = "打开悬浮窗设置失败"
    const val PERM_BATTERY_FAIL: String = "打开电池优化设置失败"

    /* OCR 引擎 */
    const val OCR_MLKIT_FAIL: String =
        "ML Kit 调用失败（第 {n} 次）：{err}。若设备无 Google Play services 属正常，会自动改用节点取词"
    const val OCR_MLKIT_EXCEPTION: String = "ML Kit 调用异常：{err}"
    const val OCR_RECOVERED: String = "主引擎 {engine} 已恢复，切回正常识别"
    const val OCR_STILL_DOWN: String = "主引擎仍不可用，继续使用 {engine}"
    const val OCR_SWITCH_FALLBACK: String =
        "主引擎 {engine} 连续 {n} 次调用失败，切换到「{fallback}」（该模式识别不了游戏画面，{sec} 秒后重试主引擎）"

    /* 开关拨动 */
    const val SWITCH_NA_YI_STATE: String = "拿翼保护：{state}"
    const val SWITCH_GUO_TU_STATE: String = "过图保护：{state}"

    /* 条件判定 */
    const val COND1_REGEX_INVALID: String = "正则非法: {kw}"

    /* 采集启动失败提示（会弹 Toast 给用户看） */
    const val HINT_CAPTURE_FAIL: String = "采集启动失败：{err}，详情见日志"
    const val HINT_CAPTURE_NO_AUTH: String = "采集启动失败：系统返回空授权，详情见日志"
    const val HINT_CAPTURE_PREEMPTED: String = "屏幕采集被录屏抢占已停止，关闭录屏后请重新点「开启屏幕监测」"
    const val HINT_IMAGEREADER_FAIL: String = "采集启动失败：ImageReader {err}"
    const val HINT_VIRTUALDISPLAY_FAIL: String = "采集启动失败：VirtualDisplay {err}，详情见日志"

    /* 触发后任务 */
    const val TASK_BUSY: String = "上一次任务尚未结束，忽略本次（{reason}）"
    const val TASK_NO_A11Y: String = "无障碍服务未开启，无法执行任务（{reason}）"
    const val TASK_TIMEOUT: String = "任务执行超时（{ms}ms），强制解锁"
    const val TASK_END_NO_CLEAR: String = "（未开启清理后台）"
}
