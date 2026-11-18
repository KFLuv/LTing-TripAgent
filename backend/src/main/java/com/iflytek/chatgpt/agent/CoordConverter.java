package com.iflytek.chatgpt.agent;

/**
 * 坐标系转换工具
 * <p>
 * 浏览器 GPS / H5 定位给出的是国际标准 WGS-84，而高德、腾讯等国内地图用的是 GCJ-02（火星坐标）。
 * 两者在国内相差约 100~500 米，直接把 WGS-84 丢给高德接口会出现明显的落点偏移。
 * 这里做 WGS-84 → GCJ-02 的偏移换算（国内通用的公开算法），供定位反查与导航链接共用。
 * <p>
 * 注意：海外坐标不做转换（GCJ-02 只在中国大陆范围内加偏）。
 */
public final class CoordConverter {

    /** 长半轴 */
    private static final double A = 6378245.0;
    /** 偏心率平方 */
    private static final double EE = 0.00669342162296594323;
    /** BD-09 偏移常量 */
    private static final double X_PI = Math.PI * 3000.0 / 180.0;

    private CoordConverter() {
    }

    /**
     * WGS-84 转 GCJ-02
     *
     * @param lat WGS-84 纬度
     * @param lng WGS-84 经度
     * @return [纬度, 经度] 的 GCJ-02 坐标
     */
    public static double[] wgs84ToGcj02(double lat, double lng) {
        if (outOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    /**
     * GCJ-02 转 WGS-84。
     *
     * <p>外部开放数据（OSM、Wikidata、标准 GeoJSON）使用 WGS-84，高德使用 GCJ-02。
     * 以高德坐标作为查询中心前，需要先反算出 WGS-84，否则周边查询圆心本身就会偏移。
     *
     * @param lat GCJ-02 纬度
     * @param lng GCJ-02 经度
     * @return [纬度, 经度] 的 WGS-84 坐标
     */
    public static double[] gcj02ToWgs84(double lat, double lng) {
        if (outOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        double wgsLat = lat;
        double wgsLng = lng;
        for (int i = 0; i < 6; i++) {
            double[] gcj = wgs84ToGcj02(wgsLat, wgsLng);
            wgsLat -= gcj[0] - lat;
            wgsLng -= gcj[1] - lng;
        }
        return new double[]{wgsLat, wgsLng};
    }

    /**
     * GCJ-02 转 BD-09。
     *
     * @param lat GCJ-02 纬度
     * @param lng GCJ-02 经度
     * @return [纬度, 经度] 的 BD-09 坐标
     */
    public static double[] gcj02ToBd09(double lat, double lng) {
        double x = lng;
        double y = lat;
        double z = Math.sqrt(x * x + y * y) + 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) + 0.000003 * Math.cos(x * X_PI);
        return new double[]{
                z * Math.sin(theta) + 0.006,
                z * Math.cos(theta) + 0.0065
        };
    }

    /**
     * BD-09 转 GCJ-02。
     *
     * @param lat BD-09 纬度
     * @param lng BD-09 经度
     * @return [纬度, 经度] 的 GCJ-02 坐标
     */
    public static double[] bd09ToGcj02(double lat, double lng) {
        double x = lng - 0.0065;
        double y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
        return new double[]{z * Math.sin(theta), z * Math.cos(theta)};
    }

    /**
     * 是否不在大陆范围内（决定要不要加偏）。
     * 纬度下限取 18 而不是常见的 0.83：常见写法把整个东南亚都算进"国内"，
     * 用户出国游时会把海外坐标也偏移约 200 米。18°N 以上覆盖海南（三亚约 18.25°N），
     * 不会误伤国内点位。
     */
    private static boolean outOfChina(double lat, double lng) {
        return lng < 73.5 || lng > 135.1 || lat < 18.0 || lat > 53.6;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
