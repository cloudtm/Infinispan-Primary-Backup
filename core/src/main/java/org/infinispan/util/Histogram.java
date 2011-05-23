package org.infinispan.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.*;

import java.io.PrintWriter;

/**
 * Created by IntelliJ IDEA.
 * User: Diego
 * Date: 22/05/11
 * Time: 14:56
 * To change this template use File | Settings | File Templates.
 */

public class Histogram {

    private AtomicInteger[] buckets;
    private int step;
    private long min;
    private long max;
    private int numBuckets;

    private String fileName="Histogram.txt";

    public Histogram(long min,long max, int step){
        this.step=step;
        this.min=min;
        this.max=max;
        this.numBuckets=(int)(max-min)/step;
        this.buckets= new AtomicInteger[numBuckets];
        for(int i=0;i<numBuckets;i++){
            this.buckets[i]=new AtomicInteger(0);
        }

    }

    public void insertSample(double sample){
        int index=determineIndex(sample);
        this.buckets[index].incrementAndGet();
    }

    private int determineIndex(double sample){
        int index;
        if(sample>max){
            index=numBuckets-1;
        }
        else if(sample<=min){
            index=0;
        }
        else{
            index =(int) ((Math.ceil((sample-min)/step))-1);
        }
        return index;
    }

    public void dumpHistogram(){
        try{
            File f= new File(this.fileName);
            PrintWriter pw= new PrintWriter(f);
            for(int k=0;k<this.numBuckets;k++){
                pw.println(step*(k+1)+"\t"+this.buckets[k].get());
            }
            pw.close();
        }
        catch(IOException e){
            e.printStackTrace();
        }
    }

}
