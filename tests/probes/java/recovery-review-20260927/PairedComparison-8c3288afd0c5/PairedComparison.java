import java.net.*;
import java.nio.file.*;

public class PairedComparison {
    public static void main(String[] args)throws Exception{
        try(var a=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},ClassLoader.getPlatformClassLoader());
            var b=new URLClassLoader(new URL[]{Path.of(args[1]).toUri().toURL()},ClassLoader.getPlatformClassLoader())){
            Class<?>[] c={a.loadClass("BenchmarkBridge"),b.loadClass("BenchmarkBridge")};
            int n=0;for(var type:c)n=(int)type.getMethod("load",String.class).invoke(null,args[2]);
            for(int w=0;w<8;w++)for(var type:c)type.getMethod("run",int.class).invoke(null,0);
            
            for(int index=0;index<n;index++)for(int trial=0;trial<3;trial++)for(int position=0;position<2;position++){
                int version=(trial+position)&1;
                Object[] r=(Object[])c[version].getMethod("run",int.class).invoke(null,index);
                System.out.println((version==0?"old":"new")+"\t"+trial+"\t"+r[0]+"\t"+r[1]+"\t"+r[2]+"\t"+r[3]);
            }
        }
    }
}
