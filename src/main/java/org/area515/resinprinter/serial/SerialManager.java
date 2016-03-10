package org.area515.resinprinter.serial;

import gnu.io.CommPortIdentifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.area515.resinprinter.display.AlreadyAssignedException;
import org.area515.resinprinter.display.InappropriateDeviceException;
import org.area515.resinprinter.printer.ComPortSettings;
import org.area515.resinprinter.printer.Printer;
import org.area515.resinprinter.projector.ProjectorModel;
import org.area515.resinprinter.server.HostProperties;
import org.area515.util.IOUtilities;

public class SerialManager {
    private static final Logger logger = LogManager.getLogger();
	private static SerialManager INSTANCE = null;
	public static final String FIRST_AVAILABLE_PORT = "First available serial port";
	public static final String AUTO_DETECT_3D_FIRMWARE = "Autodetect 3d printer firmware";
	public static final String AUTO_DETECT_PROJECTOR = "Autodetect projector";
	
	private ConcurrentHashMap<SerialCommunicationsPort, Printer> printersBySerialPort = new ConcurrentHashMap<SerialCommunicationsPort, Printer>();
	private enum ComPortReservation {
		Projector,
		PrinterFirmware
	}
	
	/** Milliseconds to block while waiting for port open */
	public static final int TIME_OUT = 2000;
	public static final int CPU_LIMITING_DELAY = 200;
	
	public static class DetectedResources {
		SerialCommunicationsPort comPort;
		ProjectorModel model;
		
		public String toString() {
			return "{comPort:" + comPort + " model:" + model + "}";
		}
	}
	
	public static SerialManager Instance() {
		if (INSTANCE == null) {
			INSTANCE = new SerialManager();
		}
		return INSTANCE;
	}
	
	private SerialManager() {
	}
	
	public ProjectorModel getProjectorModel(SerialCommunicationsPort currentIdentifier, ComPortSettings newComPortSettings) {
		ProjectorModel currentModel = null;
		for (ProjectorModel model : HostProperties.Instance().getAutodetectProjectors()) {
			try {
				logger.debug("Attempting projector model detection on:{}", newComPortSettings);
				
				currentIdentifier.open(AUTO_DETECT_PROJECTOR, TIME_OUT, newComPortSettings);
				if (model.autodetect(currentIdentifier)) {
					currentModel = model;
					break;
				}
			} catch (AlreadyAssignedException | InappropriateDeviceException e) {
				logger.debug("Failed projector model detection on:{} due to:{}", newComPortSettings, e.getMessage());
				return null;
			} finally {
				currentIdentifier.close();
			}
		}
		
		logger.debug("No projector model detected on:{}", newComPortSettings);
		return currentModel;
	}
	
	public boolean is3dFirmware(SerialCommunicationsPort currentIdentifier, ComPortSettings newComPortSettings) {
		try {
			logger.debug("Attempting 3dprinter firmware detection on:{}", newComPortSettings);
			currentIdentifier.open(AUTO_DETECT_3D_FIRMWARE, TIME_OUT, newComPortSettings);
			
			//Marlin and other firmware sends garbage on a new connect.
			String chitChat = IOUtilities.readWithTimeout(currentIdentifier, TIME_OUT, CPU_LIMITING_DELAY);
			logger.debug("Chitchat was:{} from:{}", chitChat, newComPortSettings);
			
			//Send an absolute positioning gcode and determine if the other end responds with an ok. If so, it's probably 3dFirmware.
			currentIdentifier.write("G91\r\n".getBytes());
			
			String detection = IOUtilities.readWithTimeout(currentIdentifier, TIME_OUT, CPU_LIMITING_DELAY);
			String lines[] = detection.split("\n");
			if (lines.length == 0) {
				logger.debug("No data received from:{}", newComPortSettings);
				return false;
			}
			if (lines[lines.length - 1].matches("[Oo][Kk].*")) {
				logger.debug("3dprinter firmware found on:{}", newComPortSettings);
				return true;
			}
			
			logger.debug("Unknown data:{} received on:{}", ()->{return Arrays.toString(lines);}, ()->{return newComPortSettings;});
			return false;
		} catch (InterruptedException | AlreadyAssignedException | InappropriateDeviceException | IOException e) {
			logger.debug("3dprinter firmware not found because:{}", e);
			return false;
		} finally {
			if (currentIdentifier != null) {
				currentIdentifier.close();
			}
		}
	}
	
	private DetectedResources detectResourcesAndAssignPort(Printer printer, SerialCommunicationsPort identifier, ComPortSettings newComPortSettings, ComPortReservation reservationStyle) throws AlreadyAssignedException, InappropriateDeviceException {
		logger.info("Attempting to autodetect resources for:{} using serialPort:{} with settings:{} to printer:{}", reservationStyle, identifier, newComPortSettings, printer);
		
		DetectedResources resources = new DetectedResources();
		String identifierName = identifier.getName();
		if (identifierName.equals(AUTO_DETECT_3D_FIRMWARE) && reservationStyle != ComPortReservation.PrinterFirmware) {
			throw new InappropriateDeviceException("It doesn't make sense to use:" + AUTO_DETECT_3D_FIRMWARE + " with:" + reservationStyle);
		}
		if (identifierName.equals(AUTO_DETECT_PROJECTOR) && reservationStyle != ComPortReservation.Projector) {
			throw new InappropriateDeviceException("It doesn't make sense to use:" + AUTO_DETECT_PROJECTOR + " with:" + reservationStyle);
		}
		if (identifierName.equals(ConsoleCommPort.GCODE_RESPONSE_SIMULATION) && reservationStyle != ComPortReservation.PrinterFirmware) {
			throw new InappropriateDeviceException("It doesn't make sense to use:" + ConsoleCommPort.GCODE_RESPONSE_SIMULATION + " with:" + reservationStyle);
		}
		if (identifierName.equals(FIRST_AVAILABLE_PORT) || 
			identifierName.equals(AUTO_DETECT_3D_FIRMWARE) || 
			identifierName.equals(AUTO_DETECT_PROJECTOR)) {
			identifier = null;
			ArrayList<CommPortIdentifier> identifiers = new ArrayList<CommPortIdentifier>(Collections.list(CommPortIdentifier.getPortIdentifiers()));
			for (CommPortIdentifier currentIdentifier : identifiers) {
				logger.debug("Autodetection testing serial device:{}", currentIdentifier.getName());
				
				SerialCommunicationsPort check = getSerialDevice(currentIdentifier.getName());
				newComPortSettings.setPortName(check.getName());
				
				if (!printersBySerialPort.containsKey(check)) {
					if (identifierName.equals(FIRST_AVAILABLE_PORT)) {
						identifier = check;
						break;
					}
					
					if (identifierName.equals(AUTO_DETECT_3D_FIRMWARE) && is3dFirmware(check, newComPortSettings)) {
						identifier = check;
						break;
					}
					
					if (identifierName.equals(AUTO_DETECT_PROJECTOR)) {
						ProjectorModel model = getProjectorModel(check, newComPortSettings);
						if (model != null) {
							identifier = check;
							resources.model = model;
							break;
						}
					}
				}
			}
			
			if (identifier == null) {
				newComPortSettings.setPortName(identifierName);
				throw new InappropriateDeviceException("No serial ports found for:" + identifierName);
			}
		}
		
		//This is a bit confusing, but if we are a projector and they chose their port directly, or chose FIRST_AVAILABLE_PORT, we haven't yet detected their projector model
		if (reservationStyle == ComPortReservation.Projector && !identifierName.equals(AUTO_DETECT_PROJECTOR)) {
			resources.model = getProjectorModel(identifier, newComPortSettings);
		}

		Printer otherPrintJob = printersBySerialPort.putIfAbsent(identifier, printer);
		if (otherPrintJob != null) {
			throw new AlreadyAssignedException("SerialPort already assigned to this job:" + otherPrintJob, otherPrintJob);
		}
		resources.comPort = identifier;
		logger.info("Resources detected:{}", resources);
		return resources;
	}
	
	public void assignSerialPortToProjector(Printer printer, SerialCommunicationsPort identifier) throws AlreadyAssignedException, InappropriateDeviceException {
		ComPortSettings settings = printer.getConfiguration().getMachineConfig().getMonitorDriverConfig().getComPortSettings();
		logger.info("Attempting to assign projector using serialPort:{} with settings:{} to printer:{}", identifier, settings, printer);
		if (settings == null) {
			return;
		}
		
		ComPortSettings newComPortSettings = new ComPortSettings(settings);
		DetectedResources resources = detectResourcesAndAssignPort(printer, identifier, newComPortSettings, ComPortReservation.Projector);
		identifier = resources.comPort;
		
		SerialCommunicationsPort otherPort = printer.getProjectorSerialPort();
		if (otherPort != null) {
			printersBySerialPort.remove(resources.comPort);
			throw new AlreadyAssignedException("Printer projector serial port already assigned:" + otherPort, otherPort);
		}

		if (resources.model == null) {
			printersBySerialPort.remove(resources.comPort);
			throw new InappropriateDeviceException("Couldn't determine model of projector on port:" + identifier);
		}

		try {
			identifier.open(printer.getName(), TIME_OUT, newComPortSettings);
			printer.setProjectorSerialPort(identifier);
			printer.setProjectorModel(resources.model);
			
			logger.info("Completed assignment of projector:{} using serialPort:{} with settings:{} to printer:{}", resources.model, identifier, newComPortSettings, printer);
		} catch (AlreadyAssignedException | InappropriateDeviceException e) {
			printersBySerialPort.remove(resources.comPort);
			throw e;
		}
	}
	
	public void assignSerialPortToFirmware(Printer printer, SerialCommunicationsPort identifier) throws AlreadyAssignedException, InappropriateDeviceException {
		ComPortSettings newComPortSettings = new ComPortSettings(printer.getConfiguration().getMachineConfig().getMotorsDriverConfig().getComPortSettings());
		logger.info("Attempting to assign firmware using serialPort:{} with settings:{} to printer:{}", identifier, newComPortSettings, printer);
		
		DetectedResources resources = detectResourcesAndAssignPort(printer, identifier, newComPortSettings, ComPortReservation.PrinterFirmware);
		identifier = resources.comPort;
		
		SerialCommunicationsPort otherPort = printer.getPrinterFirmwareSerialPort();
		if (otherPort != null) {
			printersBySerialPort.remove(identifier);
			throw new AlreadyAssignedException("Printer firmware serial port already assigned:" + otherPort, otherPort);
		}

		try {
			identifier.open(printer.getName(), TIME_OUT, newComPortSettings);
			printer.setPrinterFirmwareSerialPort(identifier);
			
			logger.info("Completed assignment of firmware using serialPort:{} with settings:{} to printer:{}", identifier, newComPortSettings, printer);
		} catch (AlreadyAssignedException | InappropriateDeviceException e) {
			printersBySerialPort.remove(resources.comPort);
			throw e;
		}
	}
	
	public SerialCommunicationsPort getSerialDevice(String comport) throws InappropriateDeviceException {
		// if the rxtx has problems, it will fail in getSerial
		// problem found in win 7 rxtx no class def found: io.gnu.rxtx... something like that in getSerialDevices - CommPortIdentifier.getPortIdentifiers()
		// this will start
		
		// this line shortcuts the problem for my testing.
		if(ConsoleCommPort.GCODE_RESPONSE_SIMULATION.equalsIgnoreCase(comport)){
			return new ConsoleCommPort();
		}
		for (SerialCommunicationsPort current : getSerialDevices()) {
			if  (current.getName().equals(comport)) {
				return current;
			}
		}
		
		if (comport.equals(FIRST_AVAILABLE_PORT)) {
			return new CustomCommPort(FIRST_AVAILABLE_PORT);
		}
		
		if (comport.equals(AUTO_DETECT_3D_FIRMWARE)) {
			return new CustomCommPort(AUTO_DETECT_3D_FIRMWARE);
		}
		
		if (comport.equals(AUTO_DETECT_PROJECTOR)) {
			return new CustomCommPort(AUTO_DETECT_PROJECTOR);
		}
		
		throw new InappropriateDeviceException("CommPort doesn't exist:" + comport);
	}
	
	public List<SerialCommunicationsPort> getSerialDevices() {
		List<SerialCommunicationsPort> idents = new ArrayList<SerialCommunicationsPort>();
		Enumeration<CommPortIdentifier> identifiers = CommPortIdentifier.getPortIdentifiers();
		while (identifiers.hasMoreElements()) {
			CommPortIdentifier identifier = identifiers.nextElement();
			if (identifier.getPortType() == CommPortIdentifier.PORT_SERIAL) {
				Class<SerialCommunicationsPort> communicationsClass = HostProperties.Instance().getSerialCommunicationsClass();
				SerialCommunicationsPort comPortInstance;
				try {
					comPortInstance = communicationsClass.newInstance();
					comPortInstance.setName(identifier.getName());
					idents.add(comPortInstance);
				} catch (InstantiationException | IllegalAccessException e) {
					logger.error("Error assembling serial ports", e);
				}
			}
		}
		
		idents.add(new CustomCommPort(FIRST_AVAILABLE_PORT));
		idents.add(new CustomCommPort(AUTO_DETECT_3D_FIRMWARE));
		idents.add(new CustomCommPort(AUTO_DETECT_PROJECTOR));
		
		if (HostProperties.Instance().getFakeSerial()) {
			ConsoleCommPort consolePort = new ConsoleCommPort();
			idents.add(consolePort);
		}
		
		return idents;
	}
	
	/**
	 * This should be called when you stop using the port.
	 * This will prevent port locking on platforms like Linux.
	 */
	public void removeAssignments(Printer printer) {
		logger.info("Removing serial assignments for printer:{}", printer);
		
		if (printer == null)
			return;
		
		SerialCommunicationsPort firmwarePort = printer.getPrinterFirmwareSerialPort();
		SerialCommunicationsPort projectorPort = printer.getProjectorSerialPort();
		
		if (firmwarePort != null) {
			logger.info("Removing firmware serial{} assignment for printer:{}", firmwarePort, printer);
			printersBySerialPort.remove(firmwarePort);
			printer.setPrinterFirmwareSerialPort(null);
		}
		if (projectorPort != null) {
			logger.info("Removing protector serial:{} assignment for printer:{}", projectorPort, printer);
			printersBySerialPort.remove(projectorPort);
			printer.setProjectorSerialPort(null);
		}
		
		logger.info("Clearing up projector model from printer:{}", printer);
		printer.setProjectorModel(null);
	}
}