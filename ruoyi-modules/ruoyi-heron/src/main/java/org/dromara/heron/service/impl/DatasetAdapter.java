package org.dromara.heron.service.impl;

/** 一个源一个适配器；产出必须符合 dataset/1，由 data 闸复核。 */
public interface DatasetAdapter {

    String sourceKey();

    /** 该源产出的 dataset/1 形态；抓取后与快照实际 shape 比对，不一致拒绝。 */
    String shape();

    String fetch(int limit);
}
