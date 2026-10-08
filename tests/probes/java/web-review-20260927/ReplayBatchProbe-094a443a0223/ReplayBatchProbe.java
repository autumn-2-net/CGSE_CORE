package org.cgse.core;
import java.nio.file.*;
public final class ReplayBatchProbe {
 public static void main(String[] args) throws Exception {
  BatchProbe.root=Path.of(args[0]);Files.createDirectories(BatchProbe.root);
  AdversarialProbe.out=BatchProbe.root.resolve("cases");Files.createDirectories(AdversarialProbe.out);
  BatchProbe.generate();System.out.println("GENERATED "+BatchProbe.tests.size());
  for(int i=0;i<8;i++)AdversarialProbe.run(ContrastProbe.dag(30),1000,false);
  for(var t:BatchProbe.tests)BatchProbe.one(t,3000,false,0);
 }
}
