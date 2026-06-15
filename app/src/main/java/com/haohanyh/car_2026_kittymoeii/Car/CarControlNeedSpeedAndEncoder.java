package com.haohanyh.car_2026_kittymoeii.Car;

import androidx.annotation.NonNull;

public class CarControlNeedSpeedAndEncoder {

    public CarControlNeedSpeedAndEncoder() {};
    public static CarControlNeedSpeedAndEncoder NeedManageData() { return CarControlNeedSpeedAndEncoder.managedata.thing; }
    protected static class managedata { private static final CarControlNeedSpeedAndEncoder thing = new CarControlNeedSpeedAndEncoder(); }

    /** 得到速度值
     * @param editSP 从输入速度值的控件中取值
     * @return 速度值
     */
    public int getSpeed(@NonNull String editSP) {
        int speed = 90;
        if (!editSP.equals("")) {
            speed = Integer.parseInt(editSP);
            if (speed > 0 && speed <= 100) {
                return speed;
            }
        } else {
            return speed;
        }
        return speed;
    }

    /** 得到码盘值
     * @param editEN 从输入码盘值的控件中取值
     * @return 码盘值
     */
    public int getEncoder(@NonNull String editEN) {
        int encoder = 500;
        if (!editEN.equals("")) {
            encoder = Integer.parseInt(editEN);
            if (encoder > 0 && encoder <= 65535) {
                return encoder;
            }
        } else {
            return encoder;
        }
        return encoder;
    }
}
