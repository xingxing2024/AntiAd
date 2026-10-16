package cn.server.antiad;

/** 玩家行为画像：游戏活跃度加分、命中扣分，分类决定检查强度。纯计算，方便单测。 */
public final class Behavior {
    /** 正常 = 真的在玩，提前不再花 AI；观望 = 默认；可疑 = 全量送 AI 且阈值降为 2；广告号 = 阈值 1。 */
    public enum Kind { NORMAL, WATCH, SUSPICIOUS, ADVERTISER }

    /** 原始计数：活跃度都是正的，命中扣分单独累加，分数实时算，避免对不上。 */
    public static final class Profile {
        public String name;
        public int blocks, minutes, commands, joins, penalty;
        public boolean banned;
        /** 有没有变化，用来避免每分钟把没动的玩家重写一遍。 */
        public boolean dirty;

        public Profile() {}

        public Profile(int blocks, int minutes, int commands, int joins, int penalty, boolean banned) {
            this.blocks = blocks;
            this.minutes = minutes;
            this.commands = commands;
            this.joins = joins;
            this.penalty = penalty;
            this.banned = banned;
        }
    }

    private Behavior() {}

    /** 活跃度加分：按行为算 —— 破坏方块、用指令，封顶 max-activity-score。在线时长只展示不计分。 */
    public static int activity(Settings s, int blocks, int commands) {
        int points = 0;
        if (s.blocksPerPoint > 0) points += blocks / s.blocksPerPoint;
        if (s.commandsPerPoint > 0) points += commands / s.commandsPerPoint;
        return Math.min(points, Math.max(0, s.maxActivityScore));
    }

    /** 行为分 = 基础分 + 行为加分 + 命中扣分（与时间无关）。 */
    public static int score(Settings s, Profile profile) {
        return s.defaultScore + activity(s, profile.blocks, profile.commands) + profile.penalty;
    }

    /** 之前被封过的一律算广告号。 */
    public static Kind classify(Settings s, Profile profile) {
        if (profile.banned) return Kind.ADVERTISER;
        int score = score(s, profile);
        if (score <= s.advertiserScore) return Kind.ADVERTISER;
        if (score <= s.suspiciousScore) return Kind.SUSPICIOUS;
        return score >= s.normalScore ? Kind.NORMAL : Kind.WATCH;
    }

    /** 老玩家（免检）：累计游玩时长、行为分、近 N 小时无违规，三条都满足才算。playTicks 用 PLAY_ONE_MINUTE。 */
    public static boolean veteran(Settings s, long playTicks, int score, int violations) {
        if (s.veteranPlayHours <= 0 && s.veteranMinScore <= 0 && s.veteranCleanHours <= 0) return false;
        double hours = playTicks / 72000.0;   // 1 小时 = 20 tick/s * 3600s
        return hours > s.veteranPlayHours && score >= s.veteranMinScore && violations == 0;
    }

    /** 命中一次扣的分（正数）。 */
    public static int penalty(Settings s, boolean adHit) {
        return adHit ? s.adHitPenalty : s.wordHitPenalty;
    }

    /** 该类玩家的封禁阈值：可疑降到 2，广告号 1。 */
    public static int threshold(Settings s, Kind kind) {
        return switch (kind) {
            case ADVERTISER -> 1;
            case SUSPICIOUS -> Math.min(2, s.hits);
            default -> s.hits;
        };
    }

    /** 命中后的扣分：连进连退（会话很短又没干活）也算可疑。 */
    public static boolean churn(int sessionMinutes, int sessionBlocks) {
        return sessionMinutes <= 1 && sessionBlocks <= 0;
    }
}
