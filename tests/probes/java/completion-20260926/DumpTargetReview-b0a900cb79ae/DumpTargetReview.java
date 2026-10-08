import java.nio.file.*; import java.util.*;
public class DumpTargetReview {public static void main(String[] a) throws Exception {
 DumpReplay.main(new String[]{a[0], a[1], "1", "k1240"});
 DumpReplay.output=new java.io.PrintWriter(Files.newBufferedWriter(Path.of(a[2])));
 var stock=new LinkedHashMap<>(DumpReplay.stock);stock.put("k1979",24895284983L);
 DumpReplay.run(DumpReplay.compiler("k1240"),"k1240",Integer.MAX_VALUE,"fixed_short",stock,"k1979=24895284983");
 DumpReplay.output.close();}}
