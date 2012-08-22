/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2012 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are
 * those of the authors and should not be interpreted as representing official
 * policies, either expressed or implied, of any organization.
 * #L%
 */

package imagej.core.plugins.assign;

import imagej.data.Dataset;
import imagej.data.DatasetService;
import imagej.ext.Cancelable;
import imagej.ext.menu.MenuConstants;
import imagej.ext.module.ItemIO;
import imagej.ext.plugin.RunnablePlugin;
import imagej.ext.plugin.Menu;
import imagej.ext.plugin.Parameter;
import imagej.ext.plugin.Plugin;

import java.util.HashMap;

import net.imglib2.RandomAccess;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.ops.PointSetIterator;
import net.imglib2.ops.image.ImageCombiner;
import net.imglib2.ops.operation.binary.real.RealAdd;
import net.imglib2.ops.operation.binary.real.RealAnd;
import net.imglib2.ops.operation.binary.real.RealAvg;
import net.imglib2.ops.operation.binary.real.RealBinaryOperation;
import net.imglib2.ops.operation.binary.real.RealCopyRight;
import net.imglib2.ops.operation.binary.real.RealCopyZeroTransparent;
import net.imglib2.ops.operation.binary.real.RealDifference;
import net.imglib2.ops.operation.binary.real.RealDivide;
import net.imglib2.ops.operation.binary.real.RealMax;
import net.imglib2.ops.operation.binary.real.RealMin;
import net.imglib2.ops.operation.binary.real.RealMultiply;
import net.imglib2.ops.operation.binary.real.RealOr;
import net.imglib2.ops.operation.binary.real.RealSubtract;
import net.imglib2.ops.operation.binary.real.RealXor;
import net.imglib2.ops.pointset.HyperVolumePointSet;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;

/**
 * Fills an output Dataset with a combination of two input Datasets. The
 * combination is specified by the user (such as Add, Min, Average, etc.).
 * 
 * @author Barry DeZonia
 */
@Plugin(iconPath = "/icons/plugins/calculator.png", menu = {
	@Menu(label = MenuConstants.PROCESS_LABEL,
		weight = MenuConstants.PROCESS_WEIGHT,
		mnemonic = MenuConstants.PROCESS_MNEMONIC),
	@Menu(label = "Image Calculator...", weight = 22) }, headless = true)
public class RealImageCalculator<T extends RealType<T>> implements
	RunnablePlugin, Cancelable
{

	// -- instance variables that are Parameters --

	@Parameter
	private DatasetService datasetService;

	@Parameter(type = ItemIO.BOTH)
	private Dataset input1;

	@Parameter
	private Dataset input2;

	@Parameter(type = ItemIO.OUTPUT)
	private Dataset output;

	@Parameter(label = "Operation to do between the two input images",
		choices = { "Add", "Subtract", "Multiply", "Divide", "AND", "OR", "XOR",
			"Min", "Max", "Average", "Difference", "Copy", "Transparent-zero" })
	private String opName;

	@Parameter(label = "Create new window")
	private boolean newWindow = true;

	@Parameter(label = "Floating point result")
	private boolean wantDoubles = false;

	// -- other instance variables --

	private final HashMap<String, RealBinaryOperation<T, T, DoubleType>> operators;

	private String cancelReason;

	// -- constructor --

	/**
	 * Constructs the ImageMath object by initializing which binary operations are
	 * available.
	 */
	public RealImageCalculator() {
		operators =
			new HashMap<String, RealBinaryOperation<T, T, DoubleType>>();

		operators.put("Add", new RealAdd<T, T, DoubleType>());
		operators.put("Subtract",
			new RealSubtract<T, T, DoubleType>());
		operators.put("Multiply",
			new RealMultiply<T, T, DoubleType>());
		operators.put("Divide",
			new RealDivide<T, T, DoubleType>());
		operators.put("AND", new RealAnd<T, T, DoubleType>());
		operators.put("OR", new RealOr<T, T, DoubleType>());
		operators.put("XOR", new RealXor<T, T, DoubleType>());
		operators.put("Min", new RealMin<T, T, DoubleType>());
		operators.put("Max", new RealMax<T, T, DoubleType>());
		operators.put("Average", new RealAvg<T, T, DoubleType>());
		operators.put("Difference",
			new RealDifference<T, T, DoubleType>());
		operators.put("Copy",
			new RealCopyRight<T, T, DoubleType>());
		operators.put("Transparent-zero",
			new RealCopyZeroTransparent<T, T, DoubleType>());
	}

	// -- public interface --

	/**
	 * Runs the plugin filling the output image with the user specified binary
	 * combination of the two input images.
	 */
	@Override
	public void run() {
		Img<DoubleType> img = null;
		try {
			ImageCombiner computer = new ImageCombiner();
			@SuppressWarnings("unchecked")
			Img<T> img1 = (Img<T>) input1.getImgPlus();
			@SuppressWarnings("unchecked")
			Img<T> img2 = (Img<T>) input2.getImgPlus();
			// TODO - limited by ArrayImg size constraints
			img =
				computer.applyOp(operators.get(opName), img1, img2, 
													new ArrayImgFactory<DoubleType>(), new DoubleType());
		} catch (IllegalArgumentException e) {
			cancelReason = e.toString();
			return;
		}
		long[] span = new long[img.numDimensions()];
		img.dimensions(span);

		// replace original data if desired by user
		if (!wantDoubles && !newWindow) {
			output = null;
			copyDataInto(input1.getImgPlus(), img, span);
			input1.update();
		}
		else { // write into output
			int bits = input1.getType().getBitsPerPixel();
			boolean floating = !input1.isInteger();
			boolean signed = input1.isSigned();
			if (wantDoubles) {
				bits = 64;
				floating = true;
				signed = true;
			}
			// TODO : HACK - this next line works but always creates a PlanarImg
			output =
				datasetService.create(span, "Result of operation", input1.getAxes(),
					bits, signed, floating);
			copyDataInto(output.getImgPlus(), img, span);
			output.update(); // TODO - probably unecessary
		}
	}

	@Override
	public boolean isCanceled() {
		return cancelReason != null;
	}

	@Override
	public String getCancelReason() {
		return cancelReason;
	}

	public Dataset getInput1() {
		return input1;
	}

	public void setInput1(final Dataset input1) {
		this.input1 = input1;
	}

	public Dataset getInput2() {
		return input2;
	}

	public void setInput2(final Dataset input2) {
		this.input2 = input2;
	}

	public Dataset getOutput() {
		return output;
	}

	public String getOpName() {
		return opName;
	}

	public void setOpName(final String opName) {
		this.opName = opName;
	}

	public boolean isNewWindow() {
		return newWindow;
	}

	public void setNewWindow(final boolean newWindow) {
		this.newWindow = newWindow;
	}

	public boolean isDoubleOutput() {
		return wantDoubles;
	}

	public void setDoubleOutput(final boolean wantDoubles) {
		this.wantDoubles = wantDoubles;
	}

	// -- private helpers --

	private void copyDataInto(
		Img<? extends RealType<?>> out, Img<? extends RealType<?>> in, long[] span)
	{
		RandomAccess<? extends RealType<?>> src = in.randomAccess();
		RandomAccess<? extends RealType<?>> dst = out.randomAccess();
		HyperVolumePointSet ps = new HyperVolumePointSet(new long[span.length], lastPoint(span));
		PointSetIterator iter = ps.createIterator();
		long[] pos = null;
		while (iter.hasNext()) {
			pos = iter.next();
			src.setPosition(pos);
			dst.setPosition(pos);
			double value = src.get().getRealDouble();
			dst.get().setReal(value);
		}
	}

	private long[] lastPoint(long[] span) {
		long[] lastPoint = new long[span.length];
		for (int i = 0; i < span.length; i++) {
			lastPoint[i] = span[i] - 1;
		}
		return lastPoint;
	}
}
