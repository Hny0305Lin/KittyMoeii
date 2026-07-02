package com.haohanyh.car_2026_kittymoeii.Car;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class CarLink {
    private static final String TAG = "CarLink";
    private static volatile CarLink cl;
    private byte[] sByte = new byte[35];
    private List<Byte> listbyte = new ArrayList<>();
    private final int Port = 60000;
    private volatile Socket socket;
    private volatile BufferedOutputStream OS;
    private volatile BufferedInputStream IS;
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
                    closeLink();
                    Socket newSocket = new Socket(WifiIp, Port);
                    socket = newSocket;
                    OS = new BufferedOutputStream(newSocket.getOutputStream());
                    IS = new BufferedInputStream(newSocket.getInputStream(),8192);
                    while (isConnected()) {
                        subscript=IS.read(sByte);
                        if (subscript == -1) {
                            Log.w(TAG, "小车 Socket 输入流已结束");
                            break;
                        }
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
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    closeLink();
                }
            }
        }).start();
    }

    public BufferedOutputStream getOS() {
        return OS;
    }

    public boolean isConnected() {
        Socket currentSocket = socket;
        return currentSocket != null
                && currentSocket.isConnected()
                && !currentSocket.isClosed()
                && !currentSocket.isOutputShutdown()
                && OS != null;
    }

    public synchronized void closeLink() {
        closeQuietly(IS);
        closeQuietly(OS);
        closeQuietly(socket);
        IS = null;
        OS = null;
        socket = null;
        subscript = 0;
        tag = 0;
        listbyte.clear();
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            Log.w(TAG, "关闭资源失败", e);
        }
    }
}
