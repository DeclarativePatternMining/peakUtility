package cp26.mining.patterns.util;

import java.util.ArrayList;
import java.util.List;

public class UtilityList_0 {
	public int item;
	public List<Custom_Element> elements = new ArrayList<>();
    public long sumIutil = 0;
    public long sumRutil = 0;
    public UtilityList_0(int item) { this.item = item; }
    public void add(Custom_Element e) {
        elements.add(e);
        sumIutil += e.iutil;
        sumRutil += e.rutil;
    }
}