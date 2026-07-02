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
        if (myByte == null || myByte.length < 7) {
            return 0;
        }
        JiuYuan = myByte[6];
        return JiuYuan;
    }

    public long getUltraSonic() {
        if (myByte == null || myByte.length < 6) {
            return 0;
        }
        UltraSonic = myByte[5] << 8;
        UltraSonic = UltraSonic | (myByte[4] & 0xff);
        return UltraSonic;
    }

    public long getLight() {
        if (myByte == null || myByte.length < 4) {
            return 0;
        }
        Light = myByte[3] << 8;
        Light = Light | (myByte[2] & 0xff);
        return Light;
    }

    public long getCodeDisk() {
        if (myByte == null || myByte.length < 2) {
            return 0;
        }
        CodeDisk = myByte[1] << 8;
        CodeDisk = CodeDisk | (myByte[0] & 0xff);
        return CodeDisk;
    }
}
