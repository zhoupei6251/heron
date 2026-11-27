package org.dromara.heron.service.impl.lane.databoard;

/** 一个源一个适配器；产出必须符合 dataset/1，由 data 闸复核。 */
public interface DatasetAdapter {

    /** 对应的 heron_data_source.source_key；注册表里每个 key 只能有一个适配器。 */
    String sourceKey();

    /** 该源产出的 dataset/1 形态；抓取后与快照实际 shape 比对，不一致拒绝。 */
    String shape();

    /**
     * 抓取并组装一份 dataset/1 JSON。
     *
     * @param limit 请求的最大点数或行数，具体含义由各形态自己解释
     * @return dataset/1 JSON 文本，由调用方复核 shape 与契约
     */
    String fetch(int limit);
}
