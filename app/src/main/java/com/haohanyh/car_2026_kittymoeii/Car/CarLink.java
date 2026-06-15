package com.haohanyh.car_2026_kittymoeii.Car;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class CarLink {
    private static volatile CarLink cl;
    private byte[] sByte = new byte[35];
    private List<Byte> listbyte = new ArrayList<>();
    private final int Port = 60000;
    private BufferedOutputStream OS;
    private BufferedInputStream IS;
    public int what = 0,subscript = 0,tag = 0;

    private CarLink() {
    }

    public static CarLink getCarLink() {
        if (cl == null) {
            synchronized (CarLink.class) {
                if (cl == null) {
                    cl = new CarLink();
                }
            }
        }
        return cl;
    }

    /**
     * 接口在MainActivity实现
     */
    public interface INFO {
        void DataInfo(byte[] bytes, int whatcar,int subscript);
    }

    /**
     * 得到数据的方法，使用socket，已经绑定好60000端口和Wifi的IP。
     */
    public void getDataInfo(final String WifiIp, final INFO Info) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = new Socket(WifiIp, Port);
                    OS = new BufferedOutputStream(socket.getOutputStream());
                    IS = new BufferedInputStream(socket.getInputStream(),8192);
                    while (socket != null && !socket.isClosed()) {
                        subscript=IS.read(sByte);
                        if(sByte[0]==(byte) 0x55&&sByte[subscript-1]!=(byte)0xbb){
                            if(tag==0){
                                for(int i = 0;i<subscript;i++){
                                    listbyte.add(sByte[i]);
                                }
                                tag+=subscript;
                            }
                        }else if(sByte[0]!=(byte)0x55&&sByte[subscript-1]!=(byte)0xbb){
                            for(int i = 0;i<subscript;i++){
                                listbyte.add(sByte[i]);
                            }
                            tag+=subscript;
                        }else if(sByte[0]!=(byte)0x55&&sByte[subscript-1]==(byte)0xbb){
                            for(int i = 0;i<subscript;i++){
                                listbyte.add(sByte[i]);
                            }
                            byte []libyte = new byte[listbyte.size()];
                            for(int i = 0;i<libyte.length;i++){
                                libyte[i] = listbyte.get(i);
                            }
                            tag+=subscript;
                            listbyte.clear();
                            if (libyte[1] == (byte) 0xaa) {
                                what = 6;
                                Info.DataInfo(libyte, what,tag);
                            } else if (libyte[1] == (byte) 0x02) {
                                what = 8;
                                Info.DataInfo(libyte, what,tag);
                            }
                            tag = 0;
                        }else if(sByte[0]==(byte)0x55&&sByte[subscript-1]==(byte)0xbb){
                            if (sByte[1] == (byte) 0xaa) {
                                what = 6;
                                Info.DataInfo(sByte, what,subscript);
                            } else if (sByte[1] == (byte) 0x02) {
                                what = 8;
                                Info.DataInfo(sByte, what,subscript);
                            }
                        }
                    }
                    IS.close();//优化网络体验，请先关闭BufferedInputStream避免大面积错误
                    OS.close();
                } catch (Exception e) {
                    e.printStackTrace();
                    try {
                        IS.close();//优化网络体验，请先关闭BufferedInputStream避免大面积错误
                        OS.close();
                    } catch (Exception e1) {
                        e1.printStackTrace();
                    }
                }
            }
        }).start();
    }

    public BufferedOutputStream getOS() {
        return OS;
    }
}
