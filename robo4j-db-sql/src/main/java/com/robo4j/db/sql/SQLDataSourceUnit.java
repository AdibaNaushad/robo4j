/*
 * Copyright (c) 2014, 2017, Marcus Hirt, Miroslav Wengner
 *
 * Robo4J is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Robo4J is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Robo4J. If not, see <http://www.gnu.org/licenses/>.
 */

package com.robo4j.db.sql;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.robo4j.core.AttributeDescriptor;
import com.robo4j.core.ConfigurationException;
import com.robo4j.core.DefaultAttributeDescriptor;
import com.robo4j.core.LifecycleState;
import com.robo4j.core.RoboContext;
import com.robo4j.core.RoboUnit;
import com.robo4j.core.configuration.Configuration;
import com.robo4j.core.httpunit.Constants;
import com.robo4j.core.logging.SimpleLoggingUtil;
import com.robo4j.db.sql.model.ERoboEntity;
import com.robo4j.db.sql.model.ERoboPoint;
import com.robo4j.db.sql.model.ERoboUnit;
import com.robo4j.db.sql.repository.DefaultRepository;
import com.robo4j.db.sql.repository.RoboRepository;
import com.robo4j.db.sql.support.DataSourceContext;
import com.robo4j.db.sql.support.DataSourceType;
import com.robo4j.db.sql.support.PersistenceContextBuilder;
import com.robo4j.db.sql.support.SortType;

/**
 * @author Marcus Hirt (@hirt)
 * @author Miro Wengner (@miragemiko)
 */
public class SQLDataSourceUnit extends RoboUnit<ERoboEntity> {

	private static final String ATTRIBUTE_ROBO_ALL_NAME = "all";
	private static final String ATTRIBUTE_ROBO_UNIT_ALL_ASC_NAME = "units_all_asc";
	private static final String ATTRIBUTE_ROBO_UNIT_ALL_DESC_NAME = "units_all_desc";
	private static final String ATTRIBUTE_ROBO_UNIT_ASC_NAME = "units_asc";
	private static final String ATTRIBUTE_ROBO_UNIT_DESC_NAME = "units_desc";
	private static final String ATTRIBUTE_ROBO_UNIT_POINTS_NAME = "unit_points";
	private static final Collection<AttributeDescriptor<?>> KNOWN_ATTRIBUTES = Arrays.asList(
			DefaultAttributeDescriptor.create(List.class, ATTRIBUTE_ROBO_ALL_NAME),
			DefaultAttributeDescriptor.create(List.class, ATTRIBUTE_ROBO_UNIT_ALL_ASC_NAME),
			DefaultAttributeDescriptor.create(List.class, ATTRIBUTE_ROBO_UNIT_POINTS_NAME));
	private Map<String, Object> targetUnitSearchMap = new HashMap<>();

	private static final String PERSISTENCE_UNIT = "sourceType";
	private static final String TARGET_UNIT = "targetUnit";
	private static final String PACKAGES = "packages";

	private List<Class<?>> registeredClasses;
	private DataSourceType sourceType;
	private int limit;
	private SortType sorted;
	private String[] packages;
	private DataSourceContext dataSourceContext;
	private RoboRepository repository;

	public SQLDataSourceUnit(RoboContext context, String id) {
		super(ERoboEntity.class, context, id);
	}

	@Override
	protected void onInitialization(Configuration configuration) throws ConfigurationException {
		String tmpSourceType = configuration.getString(PERSISTENCE_UNIT, null);
		if (tmpSourceType == null) {
			throw ConfigurationException.createMissingConfigNameException(PERSISTENCE_UNIT);
		}
		sourceType = DataSourceType.getByName(tmpSourceType);

		String tmpPackages = configuration.getString(PACKAGES, null);
		if (tmpPackages == null) {
			throw ConfigurationException.createMissingConfigNameException(PACKAGES);
		}

		String targetUnit = configuration.getString(TARGET_UNIT, null);
		if (targetUnit == null) {
			throw ConfigurationException.createMissingConfigNameException(TARGET_UNIT);
		}
		targetUnitSearchMap.put("unit", targetUnit);

		packages = tmpPackages.split(Constants.UTF8_COMMA);
		limit = configuration.getInteger("limit", 2);
		sorted = SortType.getByName(configuration.getString("sorted", "desc"));

	}

	@Override
	public void onMessage(ERoboEntity message) {
		Object id = repository.save(message);
		if (id == null) {
			SimpleLoggingUtil.error(getClass(), "entity not stored: " + message);
		}
	}

	@Override
	public void start() {
		setState(LifecycleState.STARTING);
		PersistenceContextBuilder builder = new PersistenceContextBuilder(sourceType, packages).build();
		registeredClasses = builder.getRegisteredClasses();
		dataSourceContext = builder.getDataSourceContext();
		repository = new DefaultRepository(dataSourceContext);
		setState(LifecycleState.STARTED);
	}

	@Override
	public void stop() {
		setState(LifecycleState.STOPPING);
		setState(LifecycleState.STOPPED);
	}

	@Override
	public void shutdown() {
		setState(LifecycleState.SHUTTING_DOWN);
		dataSourceContext.close();
		setState(LifecycleState.SHUTDOWN);
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <R> R onGetAttribute(AttributeDescriptor<R> descriptor) {
		if (descriptor.getAttributeName().equals(ATTRIBUTE_ROBO_ALL_NAME)
				&& descriptor.getAttributeType() == List.class) {
			//@formatter:off
			return (R) registeredClasses.stream().map(rc -> repository.findAllByClass(rc, SortType.ASC))
					.flatMap(List::stream)
					.collect(Collectors.toList());
			//@formatter:on
		}

		if (descriptor.getAttributeName().equals(ATTRIBUTE_ROBO_UNIT_ALL_ASC_NAME)
				&& descriptor.getAttributeType() == List.class) {
			return (R) repository.findAllByClass(ERoboUnit.class, SortType.ASC);
		}

		if (descriptor.getAttributeName().equals(ATTRIBUTE_ROBO_UNIT_ASC_NAME)
				&& descriptor.getAttributeType() == List.class) {
			return (R) repository.findByClassWithLimit(ERoboUnit.class, limit, SortType.ASC);
		}

		if (descriptor.getAttributeName().equals(ATTRIBUTE_ROBO_UNIT_ALL_DESC_NAME)
				&& descriptor.getAttributeType() == List.class) {
			return (R) repository.findAllByClass(ERoboUnit.class, SortType.DESC);
		}

		if (descriptor.getAttributeName().equals(ATTRIBUTE_ROBO_UNIT_DESC_NAME)
				&& descriptor.getAttributeType() == List.class) {
			return (R) repository.findByClassWithLimit(ERoboUnit.class, limit, SortType.DESC);
		}

		if (descriptor.getAttributeName().equals(ATTRIBUTE_ROBO_UNIT_POINTS_NAME)
				&& descriptor.getAttributeType() == List.class) {
			return (R) repository.findByFields(ERoboPoint.class, targetUnitSearchMap, limit, sorted);
		}

		return super.onGetAttribute(descriptor);
	}

	@Override
	public Collection<AttributeDescriptor<?>> getKnownAttributes() {
		return KNOWN_ATTRIBUTES;
	}

	@SuppressWarnings("unchecked")
	public <R> R getByMap(Class<?> c, Map<String, Object> map) {
		return (R) repository.findByFields(c, map, limit, sorted);
	}
}
