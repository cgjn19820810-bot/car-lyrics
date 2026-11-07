package com.wbhome.carlyrics;

/**
 * 识别总入口：**Shazam 为主（免费不限次）→ ACRCloud → AudD 兜底**。
 *
 * 为什么是这个顺序（2026-11 换 Shazam 当主力）：
 *  - Shazam 全免费、不限次、曲库大、同步快 —— 先用它探，命中率最高且不花一分钱；
 *  - ACRCloud 按次计费、AudD 免费额度极低，所以只做「Shazam 认不出」时的兜底，
 *    把额度留给真正需要的时候。
 *
 * 为什么是「串行兜底」而不是「并行抢答」：
 *  ACRCloud / AudD 按次计费，并行等于每次白烧一份额度；串行只在真失败时才多花一次。
 *  速度问题交给预录音缓冲（{@link AudioBuffer}）解决，不靠并行。
 *
 * 例外：ACRCloud 报「密钥错/额度用完」这类确定性失败时，不浪费 AudD 再试一次。
 */
public final class Recognizer {

    private Recognizer() { }

    public static AcrCloud.Result identify(byte[] wav, short[] shazamPcm, Config cfg) {
        // 1) Shazam 先探。免费，失败不心疼，直接往下走。
        //    Shazam 严格要求完整 ~12 秒，所以它不吃 wav（那是裁掉留白后的短音频），
        //    而是吃调用方单独准备的完整 12 秒 PCM（shazamPcm）。
        AcrCloud.Result shazam = Shazam.identify(shazamPcm);
        if (shazam.ok) return shazam;

        // 2) ACRCloud（配了 key 才跑）
        AcrCloud.Result acr = null;
        if (cfg.hasKey()) {
            acr = AcrCloud.identify(wav, cfg.accessKey(), cfg.accessSecret(), cfg.host());
            if (acr.ok) {
                if (acr.track.engine == null || acr.track.engine.isEmpty()) {
                    acr.track.engine = "ACRCloud";
                }
                return acr;
            }
            // 这些是确定性问题，换 AudD 也救不了，直接返回，别浪费额度
            if (acr.code == 3001 || acr.code == 3002 || acr.code == 3014) {
                acr.message = acr.message + " ｜ Shazam: " + shazam.message;
                return acr;
            }
        }

        // 3) AudD 兜底
        AcrCloud.Result audd = AudD.identify(wav, cfg.auddToken());
        if (audd.ok) {
            StringBuilder why = new StringBuilder();
            if (!shazam.ok) why.append("Shazam 没听出来");
            if (acr != null && !acr.ok) why.append("、ACRCloud 没听出来");
            if (why.length() > 0) audd.message = why + "，AudD 兜底成功";
            return audd;
        }

        // 全失败：拼一条包含三家结论的消息，方便定位
        if (acr != null) {
            acr.message = "Shazam: " + shazam.message + " ｜ " + acr.message + " ｜ AudD: " + audd.message;
            return acr;
        }
        audd.message = "Shazam: " + shazam.message + " ｜ AudD: " + audd.message;
        return audd;
    }
}
