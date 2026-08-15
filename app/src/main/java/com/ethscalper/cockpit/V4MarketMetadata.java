package com.ethscalper.cockpit;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class V4MarketMetadata {
    public final double tickSize,quantityStep,minQuantity,minNotional;
    public V4MarketMetadata(double tickSize,double quantityStep,double minQuantity,double minNotional){
        if(!(tickSize>0&&quantityStep>0&&minQuantity>=0&&minNotional>=0))throw new IllegalArgumentException("metadata");
        this.tickSize=tickSize;this.quantityStep=quantityStep;this.minQuantity=minQuantity;this.minNotional=minNotional;
    }
    public double floorQuantity(double raw,double price){
        if(!Double.isFinite(raw)||raw<=0||!Double.isFinite(price)||price<=0)return 0;
        BigDecimal q=BigDecimal.valueOf(raw), step=BigDecimal.valueOf(quantityStep);
        double result=q.divide(step,0,RoundingMode.DOWN).multiply(step).doubleValue();
        return result+1e-12<minQuantity||result*price+1e-9<minNotional?0:result;
    }
}
