package com.haohanyh.car_2026_kittymoeii.Car;

import static java.lang.Thread.sleep;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CarCommandBySocket {
    /** 详情请打开如下文件：01-2022智能嵌入式实训系统通信协议v5.1.pdf
     * 在线下载地址：http://vs.r8c.com/?page_id=1103&path=2
     * 阿里云OSS下载地址：https://bkrc-vk.oss-cn-beijing.aliyuncs.com/%E7%99%BE%E7%A7%91%E8%8D%A3%E5%88%9B%E5%A4%A7%E8%B5%9B%E8%B5%84%E6%BA%90/%E8%B5%84%E6%BA%90%E4%B8%8B%E8%BD%BD/02-%E6%96%87%E6%A1%A3%E6%95%99%E7%A8%8B/01-2024%E6%99%BA%E8%83%BD%E5%B5%8C%E5%85%A5%E5%BC%8F%E7%B3%BB%E7%BB%9F%E7%BB%BC%E5%90%88%E5%BA%94%E7%94%A8%E5%BC%80%E5%8F%91%E5%B9%B3%E5%8F%B0%E9%80%9A%E4%BF%A1%E5%8D%8F%E8%AE%AEv5.2.pdf?response-content-type=application%2Foctet-stream&OSSAccessKeyId=LTAI5tLkSjvFwtppP2qWWgdh&Expires=1782791505&Signature=RXE79Ez8u75BKuShcI4rDjnQpeo%3D
     * 怎么使用我呢？
     * 答：请import该类后实例化我：carCommandBySocket = new CarCommandBySocket();
     */
    public final byte HEAD = (byte) 0x55;           // 帧头
    public byte TYPE = 0x00;                          // 帧头
    public byte MAJOR = 0x00;                        // 主指令
    public byte FIRST = 0x00;                         // 副指令一
    public byte SECOND = 0x00;                      // 副指令二
    public byte THRID = 0x00;                         // 副指令三
    public byte CHECK_CODE = 0x00;               // 校验码
    public final byte TAIL = (byte) 0xbb;            // 帧尾

    /** 发送
     */
    private void sendSimple() {
        CHECK_CODE = getCheckDigitSimple();
        // 发送数据字节数组
        final byte[] sbyte = {HEAD, TYPE, MAJOR, FIRST, SECOND, THRID, CHECK_CODE, TAIL};
        Log.d("浩瀚银河: ", "发送于小车数据: " + Arrays.toString(sbyte));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    CarLink.getCarLink().getOS().write(sbyte, 0, sbyte.length);
                    CarLink.getCarLink().getOS().flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /** 多内容发送
     */
    private void sendMulti(byte[] bytes) {
        List<Byte> list = new ArrayList<>();
        list.add(HEAD); list.add(TYPE); list.add(MAJOR);
        for (byte aByte : bytes) {
            list.add(aByte);
        }
        list.add(getCheckDigitMulti(bytes));
        list.add(TAIL);
        final byte[] longbyte = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) {
            longbyte[i] = list.get(i);
        }
        list.clear();

        Log.d("浩瀚银河: ", "发送于多内容小车数据: " + Arrays.toString(longbyte));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (longbyte.length % 8 == 0) {
                        int y = 0;
                        for (int i = 0; i < longbyte.length / 8; i++) {
                            CarLink.getCarLink().getOS().write(longbyte, y, 8); CarLink.getCarLink().getOS().flush();
                            Log.d("浩瀚银河：", " longbyte:" + Arrays.toString(longbyte));
                            y += 8;
                        }
                    } else {
                        int y = 0;
                        for (int i = 0; i < longbyte.length / 8; i++) {
                            CarLink.getCarLink().getOS().write(longbyte, y, 8); CarLink.getCarLink().getOS().flush();
                            Log.d("浩瀚银河：", " longbyte:" + Arrays.toString(longbyte));
                            y += 8;
                        }
                        CarLink.getCarLink().getOS().write(longbyte, y, 8); CarLink.getCarLink().getOS().flush();
                        Log.d("浩瀚银河：", " longbyte:" + Arrays.toString(longbyte));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    list.clear();
                }
            }
        }).start();
    }

    /** 得到数据包校验码
     * @return 返回值
     */
    public byte getCheckDigitSimple() {
        return (byte) ((MAJOR + FIRST + SECOND + THRID) % 256);
    }

    /** 得到多数据包校验码
     * @return 返回值
     */
    public byte getCheckDigitMulti(byte[] bytes) { byte flag = 0; for (byte aByte : bytes) { flag += aByte; } flag += MAJOR;return (byte) (flag % 256); }

    /** 前进，车车移动
     * @param sp_n 速度
     * @param en_n 码盘值
     */
    public void go(int sp_n, int en_n) {
        TYPE = (byte) 0xAA;
        MAJOR = (byte) 0x02;FIRST = (byte) (sp_n & 0xFF);SECOND = (byte) (en_n & 0xFF);THRID = (byte) (en_n >> 8);
        sendSimple();
    }

    /** 后退，车车移动
     * @param sp_n 速度
     * @param en_n 码盘值
     */
    public void back(int sp_n, int en_n) {
        TYPE = (byte) 0xAA;
        MAJOR = (byte) 0x03;FIRST = (byte) (sp_n & 0xFF);SECOND = (byte) (en_n & 0xFF);THRID = (byte) (en_n >> 8);
        sendSimple();
    }

    /** 左转，车车移动
     * @param sp_n 速度
     */
    public void left(int sp_n) {
        TYPE = (byte) 0xAA;
        MAJOR = (byte) 0x04;FIRST = (byte) (sp_n & 0xFF);SECOND = (byte) 0x00;THRID = (byte) 0x00;
        sendSimple();
    }

    /** 右转，车车移动
     * @param sp_n 速度
     */
    public void right(int sp_n) {
        TYPE = (byte) 0xAA;
        MAJOR = (byte) 0x05;FIRST = (byte) (sp_n & 0xFF);SECOND = (byte) 0x00;THRID = (byte) 0x00;
        sendSimple();
    }

    /** 停车，车车移动
     */
    public void stop() {
        TYPE = (byte) 0xAA;
        MAJOR = (byte) 0x01;FIRST = (byte) 0x00;SECOND = (byte) 0x00;THRID = (byte) 0x00;
        sendSimple();
    }

    /** 循迹，寻迹，车车移动
     * @param sp_n 速度
     */
    public void line(int sp_n) {
        TYPE = (byte) 0xAA;
        MAJOR = 0x06;FIRST = (byte) (sp_n & 0xFF);SECOND = 0x00;THRID = 0x00;
        sendSimple();
    }

    /** 清空码盘值，车车移动
     */
    public void clear() {
        TYPE = (byte) 0xAA;
        MAJOR = 0x07;FIRST = 0x00;SECOND = 0x00;THRID = 0x00;
        sendSimple();
    }

    /** 指示灯，车车任务板
     * @param left 左灯
     * @param right 右灯
     */
    public void carlight(int left, int right) {
        TYPE = (byte) 0xAA;
        MAJOR = 0x20;
        if (left == 1 && right == 1) {  //临时停车
            FIRST = 0x01;SECOND = 0x01;THRID = 0x00;
            sendSimple();
        } else if (left == 1 && right == 0) {   //左转
            FIRST = 0x01;SECOND = 0x00;THRID = 0x00;
            sendSimple();
        } else if (left == 0 && right == 1) {   //右转
            FIRST = 0x00;SECOND = 0x01;THRID = 0x00;
            sendSimple();
        } else if (left == 0 && right == 0) {   //停车
            FIRST = 0x00;SECOND = 0x00;THRID = 0x00;
            sendSimple();
        }
    }

    /** 蜂鸣器，车车任务板
     * @param i 控制开启或关闭，1为开、0为关
     */
    //TODO 蜂鸣器老车30新车29
    public void carbuzzer(int i) {
        if (i == 1) FIRST = 0x01;
        else if (i == 0) FIRST = 0x00;
        TYPE = (byte) 0xAA;
        MAJOR = 0x29;SECOND = 0x00;THRID = 0x00;
        sendSimple();}

    /** RFID处理后的数据发送至小车（1）
     * @param data 需要发送的数据
     */
    public void sendRFIDDataForMainCarFirst(byte data) {
        TYPE = (byte) 0xAA; MAJOR = (byte) 0x11; FIRST = (byte) data; sendSimple();}

    /** RFID处理后的数据发送至小车（2）
     * @param data 需要发送的数据
     */
    public void sendRFIDDataForMainCarSecond(byte data) {
        TYPE = (byte) 0xAA; MAJOR = (byte) 0x15; FIRST = (byte) data; sendSimple();}

    /** TFLite处理后的数据发送到小车
     * @param data 需要发送的数据
     */
    public void sendTFDataForMainCar(byte data) {
        TYPE = (byte) 0xAA; MAJOR = (byte) 0x14; FIRST = (byte) data; sendSimple();}

    /** 飞浆处理后的数据发送到小车
     * @param datas 需要发送的数据
     */
    public void sendPaddleDataForMainCar(byte[] datas) {
        TYPE = (byte) 0xAA; MAJOR = (byte) 0x13; sendMulti(datas);}


    /** v5.1，1，光源档位增加，场景标志物
     * @param a 光源档位，1对应光源档位+1、2对应光源档位+2、3对应光源档位+3。
     */
    public void marketlampplusone(int a) {
        switch (a) {case 1: MAJOR = 0x61;break; /*光源档位+1*/  case 2: MAJOR = 0x62;break; /*光源档位+2*/ case 3: MAJOR = 0x63;break; /*光源档位+3*/}
        FIRST = 0x01;SECOND = 0x01;THRID = 0x00;
        sendSimple();
    }

    /** v5.1，3，道闸标志物控制，场景标志物
     * @param major 主指令，0x01闸门控制，0x09闸门初始角度调节，0x10车牌前三位数据(ASCII)，0x11车牌后三位数据(ASCII)，0x20回传道闸状态
     * @param first 副指令1
     * @param second 副指令2
     * @param third 副指令3
     */
    public void marketgate(int major, int first, int second, int third) {
        byte temp = TYPE;
        TYPE = 0x03;MAJOR = (byte) major;FIRST = (byte) first;SECOND = (byte) second;THRID = (byte) third;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，4，LED 显示标志物控制，场景标志物
     * marketdigital 第一排、第二排数码管显示指定数据
     * @param i 1代表显示于第一排（0x01）、2代表显示于第二排（0x02）
     * @param one 副指令，数据[1]数据[2]
     * @param two 副指令，数据[3]数据[4]
     * @param three 副指令，数据[5]数据[6]
     */
    public void marketdigital(int i, int one, int two, int three) {
        byte temp = TYPE;
        TYPE = 0x04;
        if (i == 1) {MAJOR = 0x01;FIRST = (byte) one;SECOND = (byte) two;THRID = (byte) three;}
        else if (i == 2) {MAJOR = 0x02;FIRST = (byte) one;SECOND = (byte) two;THRID = (byte) three;}
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，4，LED 显示标志物控制，场景标志物
     * marketdigital_close 第一排数码管显示计时模式计时关闭
     */
    public void marketdigital_close() {
        byte temp = TYPE;
        TYPE = 0x04;MAJOR = 0x03;FIRST = 0x00;SECOND = 0x00;THRID = 0x00;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，4，LED 显示标志物控制，场景标志物
     * marketdigital_open 第一排数码管显示计时模式计时开启
     */
    public void marketdigital_open() {
        byte temp = TYPE;
        TYPE = 0x04;MAJOR = 0x03;FIRST = 0x01;SECOND = 0x00;THRID = 0x00;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，4，LED 显示标志物控制，场景标志物
     * marketdigital_clear 第一排数码管显示计时模式计时清零
     */
    public void marketdigital_clear() {
        byte temp = TYPE;
        TYPE = 0x04;MAJOR = 0x03;FIRST = 0x02;SECOND = 0x00;THRID = 0x00;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，4，LED 显示标志物控制，场景标志物
     * marketdigital_dic 第二排数码管显示距离模式
     */
    public void marketdigital_dic(int dis) {
        byte temp = TYPE;

        int a = 0, b = 0, c = 0;
        a = (dis / 100) & (0xF);
        b = (dis % 100 / 10) & (0xF);
        c = (dis % 10) & (0xF);
        b = b << 4;
        b = b | c;

        TYPE = 0x04;MAJOR = 0x04;FIRST = 0x00;SECOND = (byte) a;THRID = (byte) b;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，5，立体车库标志物（A/B）控制，场景标志物
     * @param type 帧头，立体车库时为0x05（B）和0x0D（A）
     * @param major 主指令，0x01 0x02
     * @param first 副指令
     */
    public void marketgarage_control(int type, int major, int first) {
        byte temp = TYPE;
        TYPE = (byte) type;MAJOR = (byte) major;FIRST = (byte) first;SECOND = 0x00;THRID = 0x00;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，6，语音播报标志物（新）控制（播报指定语音命令），场景标志物
     * @param first 副指令1
     * 0x01 播报“富强路站”0x02 播报“民主路站”0x03 播报“文明路站”0x04 播报“和谐路站”0x05 播报“爱国路站”0x06 播报“敬业路站”0x07 播报“友善路站”
     */
    public void marketvoice_tts(byte first)
    {
        byte temp = TYPE;
        TYPE = (byte) 0x06;MAJOR = (byte) 0x10;FIRST = first;SECOND = (byte) 0x00;THRID = (byte) 0x00;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，6，语音播报标志物（新）控制（随机播报语音编号 01~07），场景标志物
     */
    //播报“富强路站”（编号：01）播报“民主路站”（编号：02）播报“文明路站”（编号：03）播报“和谐路站”（编号：04）播报“爱国路站”（编号：05）播报“敬业路站”（编号：06）播报“友善路站”（编号：07）
    public void marketvoice_tts()
    {
        byte temp = TYPE;
        TYPE = (byte) 0x06;MAJOR = (byte) 0x20;FIRST = (byte) 0x01;SECOND = (byte) 0x00;THRID = (byte) 0x00;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，6，语音播报标志物（新）控制（设置 RTC（实时时钟）起始日期），场景标志物
     * @param yearmonthday yearmonthday[0]为年；yearmonthday[1]为月；yearmonthday[2]为日
     */
    public void marketvoice_setRTCDate(@NonNull int[] yearmonthday)
    {
        byte temp = TYPE;
        TYPE = (byte) 0x06;MAJOR = (byte) 0x30;FIRST = (byte) yearmonthday[0];SECOND = (byte) yearmonthday[1];THRID = (byte) yearmonthday[2];
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，6，语音播报标志物（新）控制（查询 RTC（实时时钟）当前日期），场景标志物
     */
    public void marketvoice_getRTCDate()
    {
        byte temp = TYPE;
        TYPE = (byte) 0x06;MAJOR = (byte) 0x31;FIRST = (byte) 0x01;SECOND = (byte) 0x00;THRID = (byte) 0x00;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，6，语音播报标志物（新）控制（设置 RTC 起始时间），场景标志物
     * @param hour 小时
     * @param minus 分钟
     * @param second 秒
     */
    public void marketvoice_setRTCTimer(int hour, int minus, int second)
    {
        byte temp = TYPE;
        TYPE = (byte) 0x06;MAJOR = (byte) 0x40;FIRST = (byte) hour;SECOND = (byte) minus;THRID = (byte) second;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，6，语音播报标志物（新）控制（查询 RTC 当前时间），场景标志物
     */
    public void marketvoice_getRTCTimer()
    {
        byte temp = TYPE;
        TYPE = (byte) 0x06;MAJOR = (byte) 0x41;FIRST = (byte) 0x01;SECOND = (byte) 0x00;THRID = (byte) 0x00;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，6，语音播报标志物（新）控制（设置天气数据与温度数据），场景标志物
     * @param weather weather[0]为天气，大风 0x00多云 0x01晴 0x02小雪 0x03小雨 0x04阴天 0x05；weather[1]为温度（16 进制）单位℃，例如25摄氏度为19!
     */
    //那么将天气设置为大风天气，温度25℃，ZigBee控制指令为:"0x55,0x06,0x42,0x00,0x19,0x00,0x5B,0xBB"
    public void marketvoice_setWeather(@NonNull int[] weather)
    {
        byte temp = TYPE;
        TYPE = (byte) 0x06;MAJOR = (byte) 0x42;FIRST = (byte) weather[0];SECOND = (byte) weather[1];THRID = (byte) 0x00;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，6，语音播报标志物（新）控制（请求回传天气数据与温度数据），场景标志物
     */
    public void marketvoice_getWeather()
    {
        byte temp = TYPE;
        TYPE = (byte) 0x06;MAJOR = (byte) 0x43;FIRST = (byte) 0x00;SECOND = (byte) 0x00;THRID = (byte) 0x00;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，7，烽火台报警标志物控制，场景标志物
     * 需要自动获取救援点
     */
    public void marketfiretai_getid() {
        //获取救援坐标
        TYPE = 0x07;MAJOR = 0x09;FIRST = 0x00;SECOND = 0x00;THRID = 0x00;
        sendSimple();
        TYPE = (byte) 0xAA;
    }

    /** v5.1，7，烽火台报警标志物控制，场景标志物
     * @param one 0x10主指令第一个副指令，发送前三位开启码数据（需在赛场上获得数据）
     * @param two 0x10主指令第二个副指令，发送前三位开启码数据（需在赛场上获得数据）
     * @param thrid 0x10主指令第三个副指令，发送前三位开启码数据（需在赛场上获得数据）
     * @param four 0x11主指令第一个副指令，发送后三位开启码数据（需在赛场上获得数据）
     * @param five 0x11主指令第二个副指令，发送后三位开启码数据（需在赛场上获得数据）
     * @param six 0x11主指令第三个副指令，发送后三位开启码数据（需在赛场上获得数据）
     */
    public void marketfiretai_infrared(final byte one, final byte two, final byte thrid, final byte four, final byte five, final byte six) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                //发送第一份数据
                MAJOR = 0x10;FIRST = one;SECOND = two;THRID = thrid;
                sendSimple();
                new Thread(new Runnable() {
                    @Override
                    public void run() {try {
                        sleep(500);} catch (InterruptedException e) {e.printStackTrace();}}}).start();
                //延迟0.5，发送第二份数据
                MAJOR = 0x11;FIRST = four;SECOND = five;THRID = six;
                sendSimple();
                new Thread(new Runnable() {
                    @Override
                    public void run() {try {
                        sleep(500);} catch (InterruptedException e) {e.printStackTrace();}}}).start();
                //延迟0.5，发送最后一份数据
                MAJOR = 0x12;FIRST = 0x00;SECOND = 0x00;THRID = 0x00;
                sendSimple();
                new Thread(new Runnable() {
                    @Override
                    public void run() {try {
                        sleep(1000);} catch (InterruptedException e) {e.printStackTrace();}}}).start();
            }
        }).start();
    }

    /** v5.1，8，TFT显示标志物（A/B）控制，场景标志物
     * @param type 帧头，0x08(B)，0x0B(A)
     * @param major 主指令，0x10 图片显示模式，0x20 车牌显示模式前三位数据，0x21 车牌显示模式后三位数据，0x30 计时显示模式，0x40 HEX 显示模式，0x50 距离显示模式，0x60 交通标志显示模式
     * @param first 副指令1
     * @param second 副指令2
     * @param third 副指令3
     */
    public void markettft_lcd(int type, int major, int first, int second, int third) {
        byte temp = TYPE;
        TYPE = (byte) type;MAJOR = (byte) major;FIRST = (byte) first;SECOND = (byte) second;THRID = (byte) third;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，9，光照档位控制，场景标志物
     * @param dangwei 档位信息，1为加一档、2为加二档、3为加3档
     */
    public void marketlamp(int dangwei) {
        if (dangwei == 1) MAJOR = 0x61;
        else if (dangwei == 2) MAJOR = 0x62;
        else if (dangwei == 3) MAJOR = 0x63;
        FIRST = 0x00;SECOND = 0x00;THRID = 0x00;
        sendSimple();}


    /** v5.1，10，无线充电标志物控制，场景标志物
     * @param major 主指令，0x01
     * @param first 副指令1
     * @param second 副指令2
     * @param third 副指令3
     */
    public void marketcharging_magnetic_suspension(int major, int first, int second, int third) {
        byte temp = TYPE;
        TYPE = (byte) 0x0A;MAJOR = (byte) major;FIRST = (byte) first;SECOND = (byte) second;THRID = (byte) third;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，11，ETC标志物舵机角度控制，场景标志物
     * @param major 主指令，左侧舵机
     * @param first 副指令1。右侧舵机
     */
    public void marketetc_rudder_control(int major, int first) {
        byte temp = TYPE;
        TYPE = (byte) 0x0C;MAJOR = (byte) 0x08;FIRST = (byte) major;SECOND = (byte) first;THRID = 0x00;
        sendSimple();
        TYPE = temp;
    }

    /** v5.1，12，交通灯识别模式A、B，场景标志物
     */
    public void recognitionA() {try {
        sleep(500);} catch (InterruptedException e) {e.printStackTrace();}TYPE = (byte) 0x0E;MAJOR = (byte) 0x01;FIRST = 0x00;SECOND = 0x00;THRID = 0x00;
        sendSimple();}
    public void recognitionB() {try {
        sleep(500);} catch (InterruptedException e) {e.printStackTrace();}TYPE = (byte) 0x0F;MAJOR = (byte) 0x01;FIRST = 0x00;SECOND = 0x00;THRID = 0x00;
        sendSimple();}

    /** v5.1，12，交通灯识别A、B，场景标志物
     */
    public void redA() {TYPE = (byte) 0x0E;MAJOR = (byte) 0x02;FIRST = 0x01;SECOND = 0x00;THRID = 0x00;
        sendSimple();}
    public void yellowA() {TYPE = (byte) 0x0E;MAJOR = (byte) 0x02;FIRST = 0x03;SECOND = 0x00;THRID = 0x00;
        sendSimple();}
    public void greenA() {TYPE = (byte) 0x0E;MAJOR = (byte) 0x02;FIRST = 0x02;SECOND = 0x00;THRID = 0x00;
        sendSimple();}

    public void redB() {TYPE = (byte) 0x0F;MAJOR = (byte) 0x02;FIRST = 0x01;SECOND = 0x00;THRID = 0x00;
        sendSimple();}
    public void yellowB() {TYPE = (byte) 0x0F;MAJOR = (byte) 0x02;FIRST = 0x03;SECOND = 0x00;THRID = 0x00;
        sendSimple();}
    public void greenB() {TYPE = (byte) 0x0F;MAJOR = (byte) 0x02;FIRST = 0x02;SECOND = 0x00;THRID = 0x00;
        sendSimple();}

    /** 浩瀚银河预留自定义控制小车命令
     * @param major 主指令
     * @param first 副指令1
     * @param second 副指令2
     * @param third 副指令3
     * @param mainorslave 帧头，简化（主车和从车区别）0为主车1为从车
     */
    public void haohanyh_car_simple(byte major, byte first, byte second, byte third, int mainorslave) {
        //组成方法内字节数组
        byte[] temp = {TYPE,MAJOR,FIRST,SECOND,THRID};
        //分配字节数组数据
        if(mainorslave == 0) TYPE = (byte) 0xAA;
            else TYPE = (byte) 0x02;//判断主车还是从车，0是主车，1是从车
        MAJOR = major;FIRST = first;SECOND = second;THRID = third;
        //发送
        sendSimple();
        //清零
        TYPE = temp[0];MAJOR = temp[1];FIRST = temp[2];SECOND = temp[3];THRID = temp[4];
    }

    /** 浩瀚银河预留自定义控制小车命令，但多数据
     * @param bytes 数据数组
     * @param mainorslave 帧头，简化（主车和从车区别）0为主车1为从车
     */
    public void haohanyh_car_multi(byte[] bytes, int mainorslave) {
        //分配字节数组数据
        if(mainorslave == 0) TYPE = (byte) 0xAA;
        else TYPE = (byte) 0x02;//判断主车还是从车，0是主车，1是从车
        //发送
        sendMulti(bytes);
    }

    //全自动
    public void automatic() {
        TYPE = (byte) 0xaa;
        MAJOR = (byte) 0x99;
        FIRST = 0x00;
        SECOND = 0x00;
        THRID = 0x00;
        sendSimple();
    }
}
