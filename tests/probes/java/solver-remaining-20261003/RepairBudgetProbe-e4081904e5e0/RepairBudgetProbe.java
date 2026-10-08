package org.cgse.core;
public final class RepairBudgetProbe {
    public static void main(String[]args){
        int found=0;
        for(long quota:new long[]{250000,500000,1000000,2000000,4000000})for(int order=0;order<3;order++){
            var m=RepairSchedulingProbe.coloring(4,order);var budget=new PlanningBudget(0,quota,64L<<20,()->false,System::nanoTime);boolean sat=false;
            try(var n=new CountNeighborhood(m.rows(),m.lo(),m.hi(),m.point(),budget).pump(false)){
                while(!n.step()){}if(n.counts()!=null){sat=true;found++;if(!RepairSchedulingProbe.valid(m,n.counts()))throw new AssertionError("false witness");if(n.counts()[n.counts().length-1].intValueExact()!=2)throw new AssertionError("clique needs relaxed domain");}
            }
            if(budget.reservedBytes()!=0)throw new AssertionError("memory leak");
            System.out.println("quota="+quota+" order="+order+" found="+sat+" work="+budget.nodes());
        }
        System.out.println("DONE total=15 found="+found);
    }
}
