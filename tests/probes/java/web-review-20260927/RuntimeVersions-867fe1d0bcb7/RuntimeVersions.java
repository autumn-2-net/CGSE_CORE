package org.cgse.core;
import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPSolver;
public class RuntimeVersions {
 public static void main(String[] args) {
  Loader.loadNativeLibraries();
  MPSolver solver=MPSolver.createSolver("SCIP");
  System.out.println(solver.solverVersion());solver.delete();
 }
}
