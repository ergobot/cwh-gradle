package org.area515.resinprinter.job.render;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;

import javax.script.ScriptException;

public class CurrentImageRenderer implements Callable<BufferedImage> {
	private RenderingFileData data;
	private Object imageToBuild;
	private int width;
	private int height;
	
	public CurrentImageRenderer(RenderingFileData data, Object imageToBuild, int width, int height) {
		this.data = data;
		this.imageToBuild = imageToBuild;
		data.initialize(imageToBuild, width, height);
		this.width = width;
		this.height = height;
	}
	
	public BufferedImage call() throws ScriptException {
		data.slicer.colorizePolygons();
		Lock lock = data.getSpecificLock(imageToBuild);
		lock.lock();
		try {
			Graphics2D g2 = (Graphics2D)data.getCurrentImage().getGraphics();
			data.slicer.paintSlice(g2);
			RenderingFileData.ImageData imageData = data.get(imageToBuild);
			imageData.setArea((double)data.slicer.getBuildArea());
			data.getPrintFileProcessingAid().applyBulbMask(g2, width, height);
			return data.getCurrentImage();
		} finally {
			lock.unlock();
		}
	}
}