package com.haohanyh.car_2026_kittymoeii.Car;

public class CarState {
    private byte[] myByte;

    private long JiuYuan;//救援坐标
    private long UltraSonic;// 超声波
    private long Light;// 光照
    private long CodeDisk;// 码盘值

    private CarState() { }
    private static class State {
        private static final CarState CS = new CarState();
    }
    public static CarState getCarState() {
        return State.CS;
    }
    public void setByte(byte[] myByte){this.myByte = myByte;}

    public long getJiuYuan() {
        JiuYuan = myByte[6];
        return JiuYuan;
    }

    public long getUltraSonic() {
        UltraSonic = myByte[5] << 8;
        UltraSonic = UltraSonic | (myByte[4] & 0xff);
        return UltraSonic;
    }

    public long getLight() {
        Light = myByte[3] << 8;
        Light = Light | (myByte[2] & 0xff);
        return Light;
    }

    public long getCodeDisk() {
        CodeDisk = myByte[1] << 8;
        CodeDisk = CodeDisk | (myByte[0] & 0xff);
        return CodeDisk;
    }
}
