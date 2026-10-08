import java.net.*;import java.nio.file.*;import java.util.*;
public final class PairedRuntime {
 public static void main(String[] args)throws Exception {
  try(var a=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},ClassLoader.getPlatformClassLoader());var b=new URLClassLoader(new URL[]{Path.of(args[1]).toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
   String prefix="org.cgse.core.";
   var old=a.loadClass(prefix+"HandoffReview").getMethod("run",int.class);var current=b.loadClass(prefix+"HandoffReview").getMethod("run",int.class);
   for(int i=0;i<1000;i++)if(!old.invoke(null,i).equals(current.invoke(null,i)))throw new AssertionError("Transaction differs: "+i);
   System.out.println("PASS: 1000 transaction states identical to baseline, including save during provider callbacks");
   var left=a.loadClass(prefix+"ThroughputReview").getMethod("run",int.class);var right=b.loadClass(prefix+"ThroughputReview").getMethod("run",int.class);
   for(int width:new int[]{32,256,1024,4096}) {
    for(int i=0;i<8;i++){left.invoke(null,width);right.invoke(null,width);}
    List<Long> timesA=new ArrayList<>(),timesB=new ArrayList<>(),hashA=new ArrayList<>(),hashB=new ArrayList<>();
    for(int i=0;i<21;i++){long[] x,y;if(i%2==0){x=(long[])left.invoke(null,width);y=(long[])right.invoke(null,width);}else{y=(long[])right.invoke(null,width);x=(long[])left.invoke(null,width);}timesA.add(x[0]);timesB.add(y[0]);hashA.add(x[1]);hashB.add(y[1]);}
    Collections.sort(timesA);Collections.sort(timesB);Collections.sort(hashA);Collections.sort(hashB);
    System.out.println(width+"\t"+timesA.get(10)/1e6+"\t"+timesB.get(10)/1e6+"\t"+hashA.get(10)+"\t"+hashB.get(10));
   }
  }
 }
}
