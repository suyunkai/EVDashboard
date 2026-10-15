package com.kooo.evdashboard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.kooo.evdashboard.core.FilesEx;

/** Read-only root app_process helper. Never sets a vehicle property. Unknown means deny. */
public final class ParkedProbe {
    static void requireParked(Context context)throws Exception {
        RootAccess root=new RootAccess(context);root.connect();
        String cp=context.getApplicationInfo().sourceDir+":/system/framework/android.car.jar";
        String result=root.run("CLASSPATH="+FilesEx.quote(cp)+" /system/bin/app_process /system/bin com.kooo.evdashboard.ParkedProbe",18);
        FilesEx.require(result.equals("PARKED_VERIFIED"),"无法独立确认 P 挡及零车速，未执行开机音效恢复");
    }
    public static void main(String[] args) {
        Object car=null;Class<?> carClass=null;
        try {
            Looper.prepareMainLooper();
            Class<?> threadClass=Class.forName("android.app.ActivityThread");
            Object thread=threadClass.getMethod("systemMain").invoke(null);
            Context context=(Context)threadClass.getMethod("getSystemContext").invoke(thread);
            carClass=Class.forName("android.car.Car");
            // This root app_process has no registered application thread for bindService.
            // Attach to the already running Car service; missing APIs/services remain a denial.
            Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class)
                .invoke(null,"car_service");
            if(binder==null)throw new IllegalStateException("Car service unavailable");
            Class<?> serviceClass=Class.forName("android.car.ICar");
            Object service=Class.forName("android.car.ICar$Stub").getMethod("asInterface",android.os.IBinder.class)
                .invoke(null,binder);
            car=carClass.getConstructor(Context.class,serviceClass,Handler.class)
                .newInstance(context,service,new Handler(Looper.getMainLooper()));
            if(car==null)throw new IllegalStateException("Car unavailable");
            Object manager=carClass.getMethod("getCarManager",String.class).invoke(car,"property");
            for(int sample=0;sample<2;sample++) {
                Object gear=manager.getClass().getMethod("getProperty",int.class,int.class).invoke(manager,0x11400400,0);
                Object speed=manager.getClass().getMethod("getProperty",int.class,int.class).invoke(manager,0x11600207,0);
                Class<?> valueClass=Class.forName("android.car.hardware.CarPropertyValue");
                int gearStatus=(int)valueClass.getMethod("getStatus").invoke(gear);
                int speedStatus=(int)valueClass.getMethod("getStatus").invoke(speed);
                Object gv=valueClass.getMethod("getValue").invoke(gear),sv=valueClass.getMethod("getValue").invoke(speed);
                long timestamp=(long)valueClass.getMethod("getTimestamp").invoke(speed),age=SystemClock.elapsedRealtimeNanos()-timestamp;
                if(gearStatus!=0||speedStatus!=0||!(gv instanceof Integer)||((Integer)gv)!=4||
                    !(sv instanceof Float)||!Float.isFinite((Float)sv)||((Float)sv)!=0.0f||timestamp<=0||age<0||age>2_000_000_000L)
                    throw new IllegalStateException("Parking state unavailable or not stationary");
                if(sample==0)Thread.sleep(350);
            }
            System.out.println("PARKED_VERIFIED");
        }catch(Throwable error){System.err.println("Parking check unavailable; automatic changes denied");System.exit(2);}
        finally{if(car!=null)try{carClass.getMethod("disconnect").invoke(car);}catch(Throwable ignored){}}
        System.exit(0);
    }
}
