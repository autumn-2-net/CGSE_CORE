import java.net.*;import java.nio.file.*;import java.util.*;
public final class PairedObligations {
 public static void main(String[] args)throws Exception {
  try(var a=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},ClassLoader.getPlatformClassLoader());var b=new URLClassLoader(new URL[]{Path.of(args[1]).toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
   String cls="org.cgse.core.ObligationReview";
   var left=a.loadClass("org.cgse.core.InFlightReview").getMethod("run",int.class);var right=b.loadClass("org.cgse.core.InFlightReview").getMethod("run",int.class);
   var ls=a.loadClass(cls).getMethod("states",int.class);var rs=b.loadClass(cls).getMethod("states",int.class);
   for(int i=0;i<1000;i++)if(!ls.invoke(null,i).equals(rs.invoke(null,i)))throw new AssertionError("Ownership state differs "+i);
   System.out.println("PASS 160000 transaction states identical to baseline");
   for(int width:new int[]{32,256,1024,4096}) {
    for(int i=0;i<16;i++){left.invoke(null,width);right.invoke(null,width);}
    List<Long> aTime=new ArrayList<>(),bTime=new ArrayList<>(),aWork=new ArrayList<>(),bWork=new ArrayList<>();
    for(int i=0;i<51;i++){long[] x,y;if(i%2==0){x=(long[])left.invoke(null,width);y=(long[])right.invoke(null,width);}else{y=(long[])right.invoke(null,width);x=(long[])left.invoke(null,width);}aTime.add(x[0]);bTime.add(y[0]);aWork.add(x[1]);bWork.add(y[1]);}
    Collections.sort(aTime);Collections.sort(bTime);Collections.sort(aWork);Collections.sort(bWork);
    System.out.println(width+"\t"+aTime.get(25)/1e6+"\t"+bTime.get(25)/1e6+"\t"+aWork.get(25)+"\t"+bWork.get(25));
   }
  }
 }
}
