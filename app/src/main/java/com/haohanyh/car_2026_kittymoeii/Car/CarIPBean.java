package com.haohanyh.car_2026_kittymoeii.Car;

public class CarIPBean {

    public String IPCar;

    public CarIPBean() {}
    public static CarIPBean NeedManageIP() { return CarIPBean.savedata.thing; }
    protected static class savedata { private static final CarIPBean thing = new CarIPBean(); }
}
