package com.prj.tuning.maplocator.plugin;

import java.io.File;
import java.math.BigInteger;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.lang.Math;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Unmarshaller;

import com.prj.tuning.maplocator.model.LocatedMap;
import com.prj.tuning.maplocator.model.LocatedMap.Endianness;
import com.prj.tuning.maplocator.plugin.jaxb.Address;
import com.prj.tuning.maplocator.plugin.jaxb.Conversion;
import com.prj.tuning.maplocator.plugin.jaxb.Map;
import com.prj.tuning.maplocator.util.BeanUtil;
import com.prj.tuning.maplocator.util.Logger;

public class Me7XmlPlugin implements LocatorPlugin {
	private static Logger log = new Logger(Me7XmlPlugin.class);
	private static Make m;
	private static Variant v = Variant.UNDEFINED;

	public Collection<? extends LocatedMap> locateMaps(final byte[] binary) {
		PatternMatcher.clearCache();
		HashMap<String, LocatedMapWithXml> maps = new HashMap<String, LocatedMapWithXml>();
		
		m = Make.AUDI;

		byte[] volvoSearch = { 0x56, 0x4F, 0x4C, 0x56, 0x4F };	//Pattern "VOLVO"
		boolean volvo = indexOf(binary, volvoSearch) > -1;
		if (volvo) {
			m = Make.VOLVO;
		}
		
		byte[] BMWSearch = { 0x33, 0x35, 0x30, 0x34, 0x37, 0x36 };	//Pattern "BMW"
		boolean BMW = indexOf(binary, BMWSearch) > -1;
		if (BMW) {
			m = Make.BMW;
		}

		byte[] smartSearch = { 0x30, 0x32, 0x36, 0x31, 0x32, 0x30, 0x35, 0x30, 0x30 };	//Pattern "026120500"
		int smart = indexOf(binary, smartSearch);
		if (smart > -1) {
			m = Make.SMART;
			switch(binary[smart + smartSearch.length]) {
				case 0x35:
					v = Variant.SMART_450;
					break;
				case 0x36:
					v = Variant.SMART_452;
					break;
			}
		}
		
		try {
			JAXBContext ctx = JAXBContext
					.newInstance("com.prj.tuning.maplocator.plugin.jaxb");
			Unmarshaller unmarshaller = ctx.createUnmarshaller();


			File xmls = new File(System.getProperty("xml.override.dir",
					"me7xmls"));
			HashSet<String> axisIds = new HashSet<String>();

			Collection<Map> mapFiles = new HashSet<Map>();

			ExecutorService pool = Executors.newCachedThreadPool();

			parseXMLMaps(xmls, mapFiles, axisIds, unmarshaller, pool, binary);

			log.log("Pre-caching patterns...");

			pool.shutdown();
			while (!pool.isShutdown())
				pool.awaitTermination(1, TimeUnit.SECONDS);

			log.log("Patterns cached.");

			for (Map map : mapFiles) {
				LocatedMapWithXml lMap = getLocatedMap(map, binary);
				if (lMap != null) {
					maps.put(lMap.getId(), lMap);
				}
			}

			// Add axes
			for (Iterator<String> i = maps.keySet().iterator(); i.hasNext();) {
				LocatedMapWithXml lMap = maps.get(i.next());
				if (axisIds.contains(lMap.getId())) {
					lMap.setAxis(binary);
				}

				if (lMap.map.getColAxis() != null) {
					for (String id : lMap.map.getColAxis().getId()) {
						LocatedMapWithXml xAxis = maps.get(id);
						if (xAxis != null) {
							lMap.setxAxis(xAxis);
							xAxis.setAxis(binary);
							if (Boolean.TRUE.equals(lMap.map.getAfterColAxis())) {
								lMap.setAddress(xAxis.getAddress()
										+ xAxis.getWidth() * xAxis.getLength());
							}
							break;
						}
					}

					if (lMap.getxAxis() == null) {
						log.log("Removing " + lMap.getId()
								+ ", because col axis was not found.");
						i.remove();
						continue;
					}
				}

				if (lMap.map.getRowAxis() != null) {
					for (String id : lMap.map.getRowAxis().getId()) {
						LocatedMapWithXml yAxis = maps.get(id);
						if (yAxis != null) {
							lMap.setyAxis(yAxis);
							yAxis.setAxis(binary);
							if (Boolean.TRUE.equals(lMap.map.getAfterRowAxis())) {
								lMap.setAddress(yAxis.getAddress()
										+ yAxis.getWidth() * yAxis.getLength());
							}
							break;
						}
					}

					if (lMap.getyAxis() == null) {
						log.log("Removing " + lMap.getId()
								+ ", because row axis was not found.");
						i.remove();
					}
				}

			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return maps.values();
	}

	private static int getInt(byte[] binary, int offset) {
		return new BigInteger(1, new byte[] { binary[offset + 1], binary[offset] })
				.intValue() & 0x0000FFFF;
	}

	private static LocatedMapWithXml getLocatedMap(Map map, byte[] binary)
			throws Exception {

		LocatedMapWithXml lMap = new LocatedMapWithXml(map);

		int patternLocation = 0;

		// Find pattern
		if (map.getPattern() != null) {
			for (String pattern : map.getPattern()) {
				if ((patternLocation = PatternMatcher.findPattern(pattern,
						binary)) != -1)
					break;
			}

			if (patternLocation == -1) {
				log.log("Pattern for " + map.getId() + " not found.");
				return null;
			}

			log.log("Pattern for " + map.getId() + " found at: "
					+ String.format("0x%X", patternLocation));
			lMap.setAddress(getAddress(binary, map.getAddress(),
					patternLocation));
		}

		lMap.setId(map.getId());
		lMap.setTitle(map.getTitle());

		// Conversion
		transferConversion(lMap, map.getConversion());

		// Length
		if (map.getLength() != null) {
			if (map.getLength().getHardcoded() != null) {
				lMap.setLength(map.getLength().getHardcoded().intValue());
			} else {
				int lenAddr = getAddress(binary, map.getLength().getAddress(),
						patternLocation);
				int len = map.getLength().getWidth() == null ? 1 : map
						.getLength().getWidth();
				lMap.setLength(len == 1 ? binary[lenAddr] & 0xFF : getInt(
						binary, lenAddr));
			}
		}

		return lMap;
	}

	public static void transferConversion(LocatedMap lMap,
			Conversion conversion) throws Exception {

		BeanUtil.transferValue(conversion, lMap, "factor", "factor", 1.0);
		BeanUtil.transferValue(conversion, lMap, "offset", "offset", 0.0);
		BeanUtil.transferValue(conversion, lMap, "width", "width", 1);

		if (conversion != null && "LoHi".equals(conversion.getEndianness())) {
			lMap.setEndianness(Endianness.BIGENDIAN);
		} else {
			lMap.setEndianness(Endianness.LITTLEENDIAN);
		}

		BeanUtil.transferValue(conversion, lMap, "signed", "signed", false);
	}

	private static int getAddress(byte[] binary, Address address,
			int patternLocation) {
		int dpp = 0;
		switch (m) {
		case AUDI:
			dpp = 0x204;
			break;
		case VOLVO:
			dpp = 0x4;
			break;
		case BMW:
			dpp = 0x204;
			break;
		case SMART:
			switch (v) {
				case SMART_450:
					dpp = 0x4;
					break;
				case SMART_452:
					dpp = 0x6;
					break;
				default:
					dpp = 0x4;
					break;
			}
			break;
		default:
			dpp = 0204;
			break;
		}

		int addr = 0;
		if (address != null) {
			if (address.getDpp() != null) {
				dpp = new BigInteger(1, address.getDpp()).intValue() & 0x0000FFFF;
			}
			if (address.getDppOffset() != null) {
				int loc = patternLocation + address.getDppOffset();
				dpp = getInt(binary, loc);
			}
		}

		// Address
		if (address != null && address.getOffset() != null) {
			addr = getInt(binary, patternLocation + address.getOffset());
		} else {
			addr = getInt(binary, patternLocation);
		}

		switch (m) {
		case AUDI:
			return dpp * 0x4000 - 0x800000 + addr;
		case VOLVO:
			return dpp * 0x4000 - 0x800000 + addr;
		case BMW:
			return dpp * 0x4000 - 0x810000 + addr;
		case SMART:
			return dpp * 0x4000 + addr;
		default:
			return dpp * 0x4000 - 0x800000 + addr;
		}

	}

	@SuppressWarnings("unchecked")
	private static void parseXMLMaps(File files, Collection<Map> mapFiles,
			HashSet<String> axisIds, Unmarshaller unmarshaller,
			ExecutorService pool, final byte[] binary) throws Exception {
		String ext = "";
		switch (m) {
		case AUDI:
			ext = "audi.xml";
			break;
		case VOLVO:
			ext = "volvo.xml";
			break;
		case BMW:
			ext = "bmw.xml";
			break;
		case SMART:
			ext = "smart.xml";
			break;
		default:
			ext = "audi.xml";
			break;
		}
		for (File file : files.listFiles()) {
			if (file.getName().toLowerCase().endsWith(ext)) {
				final Map map = ((JAXBElement<Map>) unmarshaller.unmarshal(file)).getValue();
				if (map.getColAxis() != null) {
					axisIds.addAll(map.getColAxis().getId());
				}

				if (map.getRowAxis() != null) {
					axisIds.addAll(map.getRowAxis().getId());
				}

				mapFiles.add(map);

				// Pre-cache patterns (threaded search)
				pool.execute(new Runnable() {
					@Override
					public void run() {
						for (String pattern : map.getPattern()) {
							if (PatternMatcher.findPattern(pattern, binary) != -1)
								break;
						}
					}
				});
			} else if (file.isDirectory()) {
				parseXMLMaps(file, mapFiles, axisIds, unmarshaller, pool, binary);
			}
		}
	}

	/**
	 * Finds the first occurrence of the pattern in the text.
	 * http://stackoverflow.com/questions/1507780/searching-for-a-sequence-of-bytes-in-a-binary-file-with-java
	 */
	public static int indexOf(byte[] data, byte[] pattern) {
		int[] failure = computeFailure(pattern);

		int j = 0;
		if (data.length == 0)
			return -1;

		for (int i = 0; i < data.length; i++) {
			while (j > 0 && pattern[j] != data[i]) {
				j = failure[j - 1];
			}
			if (pattern[j] == data[i]) {
				j++;
			}
			if (j == pattern.length) {
				return i - pattern.length + 1;
			}
		}
		return -1;
	}

	/**
	 * Computes the failure function using a boot-strapping process, where the
	 * pattern is matched against itself.
	 */
	private static int[] computeFailure(byte[] pattern) {
		int[] failure = new int[pattern.length];

		int j = 0;
		for (int i = 1; i < pattern.length; i++) {
			while (j > 0 && pattern[j] != pattern[i]) {
				j = failure[j - 1];
			}
			if (pattern[j] == pattern[i]) {
				j++;
			}
			failure[i] = j;
		}

		return failure;
	}

	private static class LocatedMapWithXml extends LocatedMap {
		
		private Map map;
		
		private LocatedMapWithXml(Map map) {
			this.map = map;
			}

		private void setAxis(byte[] binary) throws Exception {
			if (!isAxis()) {
				setAxis(true);
				if (Me7XmlPlugin.m == Me7XmlPlugin.Make.SMART) setAddress(getAddress() + 2);
				if (map.getLength() == null) {
					setLength(getWidth() == 1 ? binary[getAddress()] & 0xFF
							: Me7XmlPlugin.getInt(binary, getAddress()));

					if (getWidth() == 2 && getLength() > 255) {
						setWidth(1);
						setLength(binary[getAddress()] & 0xFF);
						transferConversion(this, map.getConversion().getAlt());
					}

					setAddress(getAddress() + getWidth());
					
					if ((getLength() == 0) && (getWidth() == 1)) {
						/* 
						Support for external axis values (Smart MEG ecu is using calculated axis)
						If length is 0 this means that the values of the axis are not explicitely listed and must be calculated
						In this case we must retrieve the starting value, the step value and the total amount of values
						Then we calculate the values and store them as external values in LocatedMap
						Length will remain at 0 and addrees will be shifted to the beginning of next block therefore relative positioning of tables should still work
						XDF export will have to skip axis tables whose contains external values and will have to populate external axis values for the map tables
						*/
						int value = binary[getAddress()] & 0xFF;
						setAddress(getAddress() + getWidth());
						double step = Math.pow(2, binary[getAddress()] & 0xFF);
						setAddress(getAddress() + getWidth());
						int count = binary[getAddress()] & 0xFF;
						setAddress(getAddress() + getWidth());
						int[] values = new int[count];
						for (int i = 0; i < count; i++) {
							values[i] = value;
							value += step;
						}
						setExternal(values);
					}
				}
			}
		}
	}

	public enum Make {

		AUDI, VOLVO, BMW, SMART;

		public String value() {
			return name();
		}

		public static Make fromValue(String v) {
			return valueOf(v);
		}

	}
	
	public enum Variant {

		UNDEFINED, SMART_450, SMART_452;

		public String value() {
			return name();
		}

		public static Variant fromValue(String v) {
			return valueOf(v);
		}

	}
}
