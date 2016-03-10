package org.area515.resinprinter.printer;

public enum BuildDirection {
	Bottom_Up(1),
	Top_Down(-1);
	
	private int vector;
	
	BuildDirection(int vector) {
		this.vector = vector;
	}
	
	public int getVector() {
		return vector;
	}
}
