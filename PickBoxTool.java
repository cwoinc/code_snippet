

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.jeesite.common.constant.DataConstant;
import com.jeesite.common.service.ServiceException;
import com.jeesite.modules.business.bean.box.SimpleBox;
import com.jeesite.modules.business.entity.wah.PickBoxItem;
import lombok.Data;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.jeesite.common.constant.DictDataConstant.*;


@Slf4j
@UtilityClass
public class PickBoxTool {

    /**
     * 挑箱，默认不含缺货的信息
     */
    public List<PickBoxItem> pickBox(Param param, List<SimpleBox> pickBoxList) {
        return pickBox(param, pickBoxList, false);
    }

    /**
     * 按照仓库顺序挑箱
     * <p>
     * 只有一个仓库有货
     * 有两个仓库有货
     * 超过2个仓库有货
     * <p>
     * 各仓库依序先挑整箱
     * 各仓库依序挑整箱和非整箱
     * <p>
     * 需要更换条码XX箱
     * 需要拆箱合箱XX箱
     * 各仓库分别有XX箱
     * 各店铺站点有XX箱
     * <p>
     * 店铺站点还是要，只是不作为只挑这个店铺站点的条件，而是用于获取fnsku
     * <p>
     * 使用SKU挑选
     * 使用FNSKU挑选
     * 使用pic挑选
     * <p>
     * 挑完整箱的之后，就开始使用fnsku或者其它来挑选整箱
     * <p>
     * 保税优先
     * 混箱且整箱优先
     * 整箱优先
     * 是否考虑站点调拨（注意：有些产品，虽然pic一致，但实际上却是不能进行条码更换的）
     * 先进先出
     * 本店铺站点优先
     * 同库位优先
     * 哪个仓库需求SKU个数多，则哪个仓库优先
     */
    public List<PickBoxItem> pickBox(Param param, List<SimpleBox> pickBoxList, boolean isAppendLack) {
        if (param.getKeyNumMap().isEmpty()) {
            return Lists.newArrayList();
        }

        log.info("即将开始使用key[" + param.getKeyType() + "]挑箱，总共获取到" + pickBoxList.size() + "条记录");
        if (CollectionUtils.isEmpty(pickBoxList)) {
            return outOfStock(param, null, isAppendLack);
        }

        Function<SimpleBox, String> keyFun = getKeyFun(param);
        Map<String, Map<String, List<SimpleBox>>> wahCodeBoxCodeListMap = Maps.newHashMap();
        // 确定各仓库的挑选顺序
        Map<String, Integer> wahCodeSkuCountMap = pickBoxList.stream().peek(o -> {
            wahCodeBoxCodeListMap.compute(o.getWahCode(), (s, listMap) -> {
                if (null == listMap) {
                    listMap = Maps.newHashMap();
                }
                listMap.compute(o.getBoxCode(), (s1, boxList) -> {
                    if (null == boxList) {
                        boxList = Lists.newArrayList();
                    }
                    boxList.add(o);
                    return boxList;
                });
                return listMap;
            });
            // 使用需求SKU的个数来决定仓库挑选顺序
        }).filter(o -> param.getKeyNumMap().containsKey(keyFun.apply(o)))
                .collect(Collectors.groupingBy(SimpleBox::getWahCode, Collectors.mapping(keyFun, Collectors.collectingAndThen(Collectors.toSet(), Set::size))));

        List<Map.Entry<String, Integer>> wahMapList = Lists.newArrayList(wahCodeSkuCountMap.entrySet());
        wahMapList.sort((o1, o2) -> o2.getValue() - o1.getValue());
        log.info("仓库SKU满足量顺序如下：" + wahMapList);

        // 保税优先
        List<PickBoxItem> pickBoxItemList = Lists.newArrayList();
        List<Map.Entry<String, Integer>> surplusWahMapList = Lists.newArrayList();
        if (param.isBondedPriority()) {
            wahMapList.forEach(entry -> {
                String wahCode = entry.getKey();
                if (param.getBondedWahCodeList().contains(wahCode)) {
                    log.info("开始挑箱保税仓库[" + wahCode + "]");
                    pickBoxItemList.addAll(pickBoxByWah(param, wahCodeBoxCodeListMap.get(wahCode)));
                } else {
                    surplusWahMapList.add(entry);
                }
            });
        } else {
            surplusWahMapList.addAll(wahMapList);
        }

        // 其它仓库
        surplusWahMapList.forEach(entry -> {
            String wahCode = entry.getKey();
            log.info("开始挑箱仓库[" + wahCode + "]");
            pickBoxItemList.addAll(pickBoxByWah(param, wahCodeBoxCodeListMap.get(wahCode)));
        });

        // 缺货
        outOfStock(param, pickBoxItemList, isAppendLack);
        return pickBoxItemList;
    }

    /**
     * 从仓库挑箱子
     *
     * @param boxCodeMap 能挑的箱子列表 key 为箱子名，value为相同箱子名库存列表
     * @return 挑箱子推荐方案
     */
    private List<PickBoxItem> pickBoxByWah(Param param, Map<String, List<SimpleBox>> boxCodeMap) {
        initBoxList(param, boxCodeMap);

        List<PickBoxItem> pickBoxItemList = pickCompleteBox(param, boxCodeMap);
        pickBoxItemList.addAll(pickIncompleteBox(param, boxCodeMap));
        return pickBoxItemList;
    }

    /**
     * 挑非整箱
     */
    private List<PickBoxItem> pickIncompleteBox(Param param, Map<String, List<SimpleBox>> boxCodeMap) {
        Map<String, Long> keyNumMap = param.getKeyNumMap();
        if (keyNumMap.isEmpty()) {
            return Lists.newArrayList();
        }

        // 非整箱 原始 把非整箱和整箱未挑从所有箱子集中挑出来
        List<Box> boxList = param.getBoxList().stream().filter(box -> !param.getBoxCodes().contains(box.getBoxCode()))
                .filter(box -> checkBox(box, keyNumMap.keySet(), false)).collect(Collectors.toList());
        // 挑非整箱
        Map<String, Long> beforeKeyNumMap = Maps.newHashMap(keyNumMap);
        // 挑选后，keyNumMap的数量会减少
        List<Box> pickResultList = pickIncompleteBox(keyNumMap, param.getPositionNumMap(), boxList);

        Function<SimpleBox, String> keyFun = getKeyFun(param);
        return pickResultList.stream().map(box -> {
            param.getBoxCodes().add(box.getBoxCode());
            return boxCodeMap.get(box.getBoxCode()).stream().map(o -> {
                PickBoxItem pickBoxItem = getPickBoxItem(param.getPickCode(), o);
                pickBoxItem.setPickType(PICK_BOX_INCOMPLETE);
                String key = keyFun.apply(o);
                if (beforeKeyNumMap.containsKey(key)) {
                    beforeKeyNumMap.merge(key, -o.getNum(), Long::sum);
                    Long skuNeedNum = beforeKeyNumMap.get(key);
                    long pickNum = skuNeedNum + o.getNum();
                    pickBoxItem.setPickNum(skuNeedNum < 0 ? pickNum < 0 ? 0 : pickNum : o.getNum());
                } else {
                    pickBoxItem.setPickNum(0L);
                }
                pickBoxItem.setInbSign(o.getInbCodeMatching(param.getShopName(), param.getMarketplace()));
                return pickBoxItem;
            }).collect(Collectors.toList());
        }).flatMap(Collection::stream).collect(Collectors.toList());
    }

    /**
     * 挑整箱
     */
    private List<PickBoxItem> pickCompleteBox(Param param, Map<String, List<SimpleBox>> boxCodeMap) {
        // 挑整箱
        return pickCompleteBox(param).stream().peek(o -> param.getBoxCodes().add(o.getBoxCode()))
                .map(box -> boxCodeMap.get(box.getBoxCode()).stream().map(o -> {
                    PickBoxItem pickBoxItem = getPickBoxItem(param.getPickCode(), o);
                    pickBoxItem.setPickType(PICK_BOX_COMPLETE);
                    pickBoxItem.setPickNum(pickBoxItem.getNum());
                    pickBoxItem.setInbSign(o.getInbCodeMatching(param.getShopName(), param.getMarketplace()));
                    return pickBoxItem;
                }).collect(Collectors.toList())).flatMap(Collection::stream).collect(Collectors.toList());
    }

    /**
     * 缺货
     */
    public List<PickBoxItem> outOfStock(Param param, List<PickBoxItem> pickBoxItems, boolean isAppendLack) {
        List<PickBoxItem> list = null == pickBoxItems ? Lists.newArrayList() : pickBoxItems;
        if (!isAppendLack) {
            if (KeyType.SKU.equals(param.getKeyType())) {
                Map<String, Long> keyNumMap = param.getKeyNumMap();
                if (CollectionUtils.isEmpty(keyNumMap)) {
                    return Lists.newArrayList();
                }
                param.setLackSkuNumMap(Maps.newHashMap(keyNumMap));
            } else {
                updateLackNum(param);
            }
            return list;
        }

        updateLackNum(param);
        param.getLackSkuNumMap().forEach((sku, needNum) -> {
            PickBoxItem pickBoxItem = getPickBoxItem(param.getPickCode(), null);
            pickBoxItem.setPickType(PICK_BOX_LACK);
            pickBoxItem.setSku(sku);
            pickBoxItem.setPickNum(needNum);
            list.add(pickBoxItem);
        });
        return list;
    }

    /**
     * 更新缺货数量
     */
    private void updateLackNum(Param param) {
        Map<String, List<String>> skuKeyListMap = param.getSkuKeyListMap();
        Map<String, Long> skuNumMap = Maps.newHashMap(param.getLackSkuNumMap());
        AtomicLong oldNum = new AtomicLong();

        Map<String, Long> newSkuNumMap = Maps.newHashMap();
        skuNumMap.forEach((sku, num) -> {
            List<String> list = skuKeyListMap.get(sku);
            if (CollectionUtils.isEmpty(list)) {
                // 表明该sku没有参与本次挑选
                newSkuNumMap.merge(sku, num, Long::sum);
                return;
            }

            oldNum.set(num);
            param.getKeyNumMap().forEach((key, n) -> {
                if (!list.contains(key)) {
                    return;
                }

                long l;
                if (oldNum.get() > n) {
                    l = n;
                    oldNum.addAndGet(-n);
                } else {
                    l = oldNum.get();
                    oldNum.set(0);
                }

                newSkuNumMap.compute(sku, (s, m) -> {
                    if (null == m) {
                        return l;
                    }
                    return l + m;
                });
            });
        });

        Map<String, Long> lackSkuNumMap = param.getLackSkuNumMap();
        lackSkuNumMap.clear();
        skuNumMap.forEach((sku, num) -> {
            long newNum = newSkuNumMap.getOrDefault(sku, 0L);
            if (newNum > num) {
                throw new ServiceException("剩余挑箱数量" + newNum + "小于挑箱前数量" + num);
            }
            long diff = num - newNum;
            if (num == diff) {
                return;
            }
            lackSkuNumMap.put(sku, num - diff);
        });
        log.info("剩余缺货数量：" + lackSkuNumMap);
    }

    /**
     * 获取挑箱详情
     */
    private PickBoxItem getPickBoxItem(String pickCode, SimpleBox o) {
        PickBoxItem pickBoxItem = new PickBoxItem();
        pickBoxItem.initDefault();
        if (null != o) {
            BeanUtils.copyProperties(o, pickBoxItem, DataConstant.ENTITY_PROPERTIES);
        }
        pickBoxItem.setPickCode(pickCode);
        return pickBoxItem;
    }

    /**
     * 挑整箱
     *
     * @return 挑选结果
     */
    private List<Box> pickCompleteBox(Param param) {
        // 整箱 原始 把整箱从所有箱子集中挑出来
        List<Box> boxList = param.getBoxList().stream().filter(box -> checkBox(box, param.getKeyNumMap().keySet(), true)).collect(Collectors.toList());

        long l1 = System.currentTimeMillis();
        List<Box> pickedBoxList = greedyPickCompleteBox(param, boxList);
        long l2 = System.currentTimeMillis();

        logSkuNum(pickedBoxList, "挑选整箱的Key数量");
        log.info("挑选整箱耗时" + (l2 - l1) + "毫秒，剩余需求情况：" + param.getKeyNumMap());
        return pickedBoxList;
    }

    /**
     * 挑非整箱
     *
     * @param keyNumMap 当前需求sku
     * @return 挑选结果
     */
    private List<Box> pickIncompleteBox(Map<String, Long> keyNumMap, Map<String, Long> positionNumMap, List<Box> boxList) {
        long l3 = System.currentTimeMillis();
        List<Box> pickedResultList = greedyPickIncompleteBox(keyNumMap, positionNumMap, boxList);
        long l4 = System.currentTimeMillis();

        logSkuNum(pickedResultList, "挑选非整箱的Key数量");
        log.info("挑选非整箱耗时" + (l4 - l3) + "毫秒，剩余需求：" + keyNumMap);
        return pickedResultList;
    }

    /**
     * 判断当前箱子是否是整箱
     *
     * @param box     当前箱子
     * @param needSku 需求sku
     * @param fullBox 整箱
     * @return 是不是整箱
     */
    private boolean checkBox(Box box, Set<String> needSku, boolean fullBox) {
        for (KeyNum keyNum : box.getKeyNumList()) {
            String sku = keyNum.getKey();
            if (fullBox) {
                if (!needSku.contains(sku)) {
                    return false;
                }
            } else {
                if (needSku.contains(sku)) {
                    return true;
                }
            }
        }
        return fullBox;
    }

    /**
     * 初始化仓库数据
     *
     * @param listMap 库存箱子Map
     */
    private void initBoxList(Param param, Map<String, List<SimpleBox>> listMap) {
        Function<SimpleBox, String> keyFun = getKeyFun(param);
        // 箱子数据Map
        param.getBoxList().clear();
        listMap.forEach((boxCode, boxList) -> {
            if (CollectionUtils.isEmpty(boxList)) {
                throw new ServiceException("箱子编码:" + boxCode + "数据错误，库存信息不存在！");
            }

            String positionCode = Optional.ofNullable(boxList.get(0).getPositionCode())
                    .orElseThrow(() -> new ServiceException("boxCode:" + boxCode + "数据错误，库位编码不存在！"));

            Map<String, KeyNum> keyNumList = boxList.stream().collect(Collectors.toMap(keyFun, box -> {
                KeyNum keyNum = new KeyNum();
                BeanUtils.copyProperties(box, keyNum);
                keyNum.setInbCode(box.getInbCodeMatching(param.getShopName(), param.getMarketplace()));
                keyNum.setKey(keyFun.apply(box));
                return keyNum;
            }, (o1, o2) -> {
                KeyNum keyNum = o1.getInbCode().compareTo(o2.getInbCode()) < 0 ? o1 : o2;
                keyNum.setNum(o1.getNum() + o2.getNum());
                return keyNum;
            }));

            Box box = new Box();
            box.setBoxCode(boxCode);
            box.setPositionCode(positionCode);
            box.setKeyNumList(Lists.newArrayList(keyNumList.values()));
            box.setMixedSize(keyNumList.size());
            box.setMinInbCode(keyNumList.values().stream().map(KeyNum::getInbCode).min(String::compareTo)
                    .orElseThrow(() -> new ServiceException("boxCode:" + boxCode + "数据错误，创建时间不存在！")));
            param.getBoxList().add(box);
        });
    }

    /**
     * 获取key方法
     */
    private Function<SimpleBox, String> getKeyFun(Param param) {
        Function<SimpleBox, String> keyFun;
        switch (param.getKeyType()) {
            case SKU:
                keyFun = SimpleBox::getSku;
                break;
            case FNSKU:
                keyFun = SimpleBox::getFnsku;
                break;
            case PIC:
                keyFun = SimpleBox::getPic;
                break;
            default:
                throw new ServiceException("KeyType[" + param.getKeyType() + "]不存在");
        }
        return keyFun;
    }

    /**
     * 打印挑选结果
     *
     * @param boxes 箱子
     */
    private void logSkuNum(List<Box> boxes, String tips) {
        Map<String, Long> skuCountMap = Maps.newHashMap();
        boxes.forEach(box -> box.getKeyNumList().forEach(e -> {
            skuCountMap.merge(e.getKey(), e.getNum(), Long::sum);
            log.info("boxCode:" + box.getBoxCode() + "-" + e.getKey() + "-" + e.getNum());
        }));
        log.info(tips + "：" + skuCountMap);
    }

    /**
     * 计算整箱挑选相对较优方案(贪心选择)
     *
     * @param param 所需Sku
     * @return 相对较优方案
     */
    private List<Box> greedyPickCompleteBox(Param param, List<Box> boxList) {
        boxList.forEach(box -> box.setMaxUsable(box.getKeyNumList().stream().map(KeyNum::getNum).mapToLong(Long::longValue).sum()));

        // 是否按照混箱且整箱优先执行
        // 注意慎用.reversed()，容易出现排序失效的情况，尤其是同时使用了多个的情况
        Comparator<Box> mixedComp = param.mixedAndFull ? Comparator.comparing(Box::getMixedSize, Collections.reverseOrder()) : Comparator.comparing(Box::getMixedSize);

        Map<String, Long> keyNumMap = param.getKeyNumMap();
        Map<String, Long> positionNumMap = param.getPositionNumMap();
        Comparator<Box> comparator = mixedComp.thenComparing(Box::getMaxUsable, Collections.reverseOrder())
                .thenComparing(box -> positionNumMap.getOrDefault(box.getPositionCode(), 0L), Collections.reverseOrder())
                .thenComparing(Box::getMinInbCode);

        List<Box> pickedBoxList = Lists.newArrayList();
        // 这里的排除仅限于当前方法，因此可以放心添加
        Set<String> excludeBoxCodes = Sets.newHashSet();
        boxFlag:
        for (int i = 0; i < boxList.size(); i++) {
            // 按照混箱SKU数量、可参与挑选数量倒序、库位选中量倒序、最小入库编码的寻找第一个含需挑选的SKU的箱子信息
            Box pickedBox = boxList.stream().filter(box -> !excludeBoxCodes.contains(box.getBoxCode()))
                    // min 在这里可以理解成第一个 max 在这里可以理解成最后一个
                    .filter(box -> box.getKeyNumList().stream().anyMatch(keyNum -> keyNumMap.containsKey(keyNum.getKey()))).min(comparator).orElse(null);
            // 不用担心keyNumMap没有值后还会下去，因为当keyNumMap没有值时，箱子一定是null
            if (null == pickedBox) {
                break;
            }

            List<KeyNum> keyNumList = pickedBox.getKeyNumList();
            for (KeyNum keyNum : keyNumList) {
                Long needNum = keyNumMap.get(keyNum.getKey());
                // needNum为空，当前所需Sku被移除了，因为正好满足需求了。
                if (null == needNum) {
                    excludeBoxCodes.add(pickedBox.getBoxCode());
                    continue boxFlag;
                }

                // 剩余数量
                long residue = keyNumMap.get(keyNum.getKey()) - keyNum.getNum();
                // 剩余数量小于0 不要这个箱子 因为先不要拆整箱 （整箱不拆箱）
                if (residue < 0) {
                    excludeBoxCodes.add(pickedBox.getBoxCode());
                    continue boxFlag;
                }
            }

            for (KeyNum keyNum : keyNumList) {
                // 剩余数量
                long residue = keyNumMap.get(keyNum.getKey()) - keyNum.getNum();
                // 剩余数量不存在小于0的情况 因为上面已经滤掉了
                if (residue == 0) {
                    keyNumMap.remove(keyNum.getKey());
                } else {
                    keyNumMap.put(keyNum.getKey(), residue);
                }
            }
            positionNumMap.merge(pickedBox.getPositionCode(), 1L, Long::sum);

            pickedBoxList.add(pickedBox);
            excludeBoxCodes.add(pickedBox.getBoxCode());
        }
        return pickedBoxList;
    }

    /**
     * 计算非整箱挑选相对较优方案(贪心选择)
     *
     * @param keyNumMap 所需Pic
     * @return 相对较优方案
     */
    private List<Box> greedyPickIncompleteBox(Map<String, Long> keyNumMap, Map<String, Long> positionNumMap, List<Box> boxList) {
        // 注意慎用.reversed()，容易出现排序失效的情况，尤其是同时使用了多个的情况
        Comparator<Box> comparator = Comparator.comparing(Box::getMaxNeedUsable, Collections.reverseOrder())
                .thenComparingLong(box -> box.getMaxUsable() - box.getMaxNeedUsable())
                .thenComparing(box -> positionNumMap.getOrDefault(box.getPositionCode(), 0L), Collections.reverseOrder())
                .thenComparing(Box::getMinInbCode);

        Predicate<KeyNum> predicate = o -> keyNumMap.containsKey(o.getKey());

        Consumer<Box> consumer = box -> {
            box.setMaxUsable(0L);
            long maxNeedUsable = box.getKeyNumList().stream().peek(o -> box.appendMaxUsable(o.getNum()))
                    .filter(predicate)
                    .peek(keyNum -> {
                        String minInbCode = box.getMinInbCode();
                        String inbCode = keyNum.getInbCode();
                        box.setMinInbCode(inbCode.compareTo(minInbCode) < 0 ? inbCode : minInbCode);
                    }).mapToLong(keyNum -> Math.min(keyNum.getNum(), keyNumMap.get(keyNum.getKey()))).sum();
            box.setMaxNeedUsable(maxNeedUsable);
        };

        List<Box> pickedBoxList = Lists.newArrayList();
        Set<String> excludeBoxCodes = Sets.newHashSet();
        while (keyNumMap.size() != 0) {
            // 过滤掉已经选了的 和 不在当前所需pic中的
            // 计算最多可用数量 和 可用货最早入库时间
            // 最大需求可用数量 > 冗余数量最少 > 库位 > 入库时间
            Box pickedBox = boxList.stream().filter(box -> !excludeBoxCodes.contains(box.getBoxCode()))
                    .filter(box -> box.getKeyNumList().stream().anyMatch(predicate))
                    // 计算最多可用数量 和 可用货最早入库时间
                    .peek(consumer).min(comparator).orElse(null);
            // 无法找到满足条件的值时跳出
            if (null == pickedBox) {
                break;
            }

            pickedBoxList.add(pickedBox);
            excludeBoxCodes.add(pickedBox.getBoxCode());
            positionNumMap.merge(pickedBox.getPositionCode(), 1L, Long::sum);

            pickedBox.getKeyNumList().stream().filter(predicate).forEach(keyNum -> {
                Long needPicNum = keyNumMap.get(keyNum.getKey());
                // 剩余数量
                long residue = needPicNum - keyNum.getNum();
                if (residue <= 0) {
                    keyNumMap.remove(keyNum.getKey());
                } else {
                    keyNumMap.put(keyNum.getKey(), residue);
                }
            });
        }
        return pickedBoxList;
    }

    public enum KeyType {
        SKU, FNSKU, PIC
    }

    @Data
    public static class KeyNum {
        /**
         * sku、fnsku、pic
         */
        private String key;
        private Long num;
        /**
         * 用入库单编码后面的时间戳
         */
        private String inbCode;
    }

    @Data
    private static class Box {
        private String boxCode;
        private String positionCode;
        private int mixedSize;
        private List<KeyNum> keyNumList;
        /**
         * 最多可用数量
         */
        private Long maxUsable;
        /**
         * 需求SKU的最多可用数量
         */
        private Long maxNeedUsable;
        /**
         * 用入库单编码后面的时间戳
         * 所有sku中最早创建(入库)时间
         */
        private String minInbCode;

        void appendMaxUsable(long l) {
            if (null == maxUsable) {
                maxUsable = l;
                return;
            }
            maxUsable += l;
        }

    }

    @Data
    public static class Param {

        private String pickCode;
        private String shopName;
        private String marketplace;

        /**
         * 本次需要挑选的Key和数量
         */
        private Map<String, Long> keyNumMap;
        /**
         * 保税仓库的仓库编码列表
         */
        private List<String> bondedWahCodeList;

        /**
         * 是否保税优先，默认否
         */
        private boolean bondedPriority;
        /**
         * 是否混箱且整箱优先，默认否
         */
        private boolean mixedAndFull;
        /**
         * 本次挑选的key信息，默认使用SKU
         */
        private KeyType keyType = KeyType.SKU;

        /**
         * 库位的选中情况，相同库位的优先级更高
         */
        private Map<String, Long> positionNumMap = Maps.newHashMap();
        /**
         * 仓库中的箱子转换后的箱子信息
         */
        private List<Box> boxList = Lists.newArrayList();
        /**
         * 已挑选的箱子编码列表
         */
        private Set<String> boxCodes = Sets.newHashSet();

        /**
         * 用于将key转换回SKU
         */
        private Map<String, List<String>> skuKeyListMap = Maps.newHashMap();
        private Map<String, Long> lackSkuNumMap;

        /**
         * 更新key相关信息
         */
        public void updateKeys(Map<String, Long> keyNumMap, KeyType keyType) {
            this.keyNumMap = keyNumMap;
            this.keyType = keyType;
            boxList.clear();
        }

    }

}
