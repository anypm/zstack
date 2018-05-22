package org.zstack.zql.ast;

import org.apache.commons.lang.StringUtils;
import org.zstack.core.db.EntityMetadata;
import org.zstack.header.core.StaticInit;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.query.*;
import org.zstack.header.rest.APINoSee;
import org.zstack.header.search.Inventory;
import org.zstack.header.search.Parent;
import org.zstack.header.search.TypeField;
import org.zstack.utils.*;
import org.zstack.utils.logging.CLogger;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

import static org.codehaus.groovy.runtime.InvokerHelper.asList;

public class ZQLMetadata {
    private static final CLogger logger = Utils.getLogger(ZQLMetadata.class);

    public static class ExpandQueryMetadata {
        public Class selfVOClass;
        public Class targetVOClass;
        public Class targetInventoryClass;
        public String selfKeyName;
        public String targetKeyName;
        public String name;
        public boolean hidden;
    }

    public static class ExpandQueryAliasMetadata {
        public String aliasName;
        public String expandQueryText;
    }

    public static class InventoryMetadata {
        public Class selfInventoryClass;
        public transient Inventory inventoryAnnotation;
        public Map<String, ExpandQueryMetadata> expandQueries = new HashMap<>();
        public Map<String, ExpandQueryAliasMetadata> expandQueryAliases = new HashMap<>();
        public Set<String> selfInventoryFieldNames;

        public boolean hasInventoryField(String fname) {
            return selfInventoryFieldNames.contains(fname);
        }

        public void errorIfNoField(String fname) {
            if (!hasInventoryField(fname)) {
                throw new CloudRuntimeException(String.format("inventory[${selfInventoryClass}] has no field[%s]", fname));
            }
        }

        public boolean isUs(String inventoryName) {
            return selfInventoryClass.getSimpleName().equalsIgnoreCase(inventoryName);
        }

        public String fullInventoryName() {
            return selfInventoryClass.getName();
        }

        public String simpleInventoryName() {
            return selfInventoryClass.getSimpleName();
        }
    }

    /**
     * key: the full class name of inventory, e.g. org.zstack.host.HostInventory
     * value: InventoryMetadata
     */
    public static Map<String, InventoryMetadata> inventoryMetadata = new HashMap<>();
    /**
     * key: parent inventory class
     * value:
     *      key: type
     *      value: child inventory class
     */
    public static Map<Class, Map<String, Class>> typeFieldToParentInventory = new HashMap<>();
    /**
     * key: inventory class
     * value: field annotated by @TypeField
     */
    public static Map<Class, Field> inventoryTypeFields = new HashMap<>();

    public interface ChainQueryStruct {
        default void verify() {
        }
    }

    public static InventoryMetadata findInventoryMetadata(String queryName) {
        String qname = String.format("%sinventory", queryName);
        Optional<InventoryMetadata> opt = inventoryMetadata.values().stream().filter(i->i.isUs(qname)).findFirst();
        DebugUtils.Assert(opt.isPresent(), String.format("cannot find inventory with name[%s]", queryName));
        return opt.get();
    }

    public static InventoryMetadata getInventoryMetadataByName(String name) {
        InventoryMetadata m = inventoryMetadata.get(name);
        DebugUtils.Assert(m != null, String.format("cannot find metadata for inventory class[%s]", name));
        return m;
    }

    public static class FieldChainQuery implements ChainQueryStruct {
        public InventoryMetadata self;
        public ExpandQueryMetadata right;
        public String fieldName;

        @Override
        public void verify() {
            if (right != null) {
                InventoryMetadata him = getInventoryMetadataByName(right.targetInventoryClass.getName());
                if (!him.hasInventoryField(fieldName)) {
                    throw new CloudRuntimeException(String.format("inventory class[%s] not having field[%s]", him.selfInventoryClass, fieldName));
                }
            } else {
                if (!self.hasInventoryField(fieldName)) {
                    throw new CloudRuntimeException(String.format("inventory class[%s] not having field[%s]", self.selfInventoryClass, fieldName));
                }
            }
        }


        @Override
        public String toString() {
            return "FieldChainQuery{" +
                    "self=" + self.selfInventoryClass +
                    ", right=" + right.name +
                    ", fieldName='" + fieldName + '\'' +
                    '}';
        }
    }

    public static class ExpandChainQuery implements ChainQueryStruct {
        public String selfKey;
        public InventoryMetadata self;
        public ExpandQueryMetadata right;


        @Override
        public String toString() {
            return "ExpandChainQuery{" +
                    "selfKey='" + selfKey + '\'' +
                    ", self=" + self.selfInventoryClass +
                    ", right=" + right.name +
                    '}';
        }
    }

    public static List<ChainQueryStruct> createChainQuery(String inventoryName, List<String> nestConditionNames) {
        return new ChainQueryStructGetter(inventoryName, nestConditionNames).get();
    }

    private static class ChainQueryStructGetter {
        String inventoryName;
        List<String> nestConditionNames;

        public ChainQueryStructGetter(String inventoryName, List<String> nestConditionNames) {
            this.inventoryName = inventoryName;
            this.nestConditionNames = nestConditionNames;
        }

        List<ChainQueryStruct> get() {
            DebugUtils.Assert(!nestConditionNames.isEmpty(), String.format("empty nestConditionNames for inventoryName[%s]", inventoryName));

            InventoryMetadata metadata = inventoryMetadata.get(inventoryName);
            if (metadata == null) {
                throw new CloudRuntimeException(String.format("cannot find metadata for query target[%s]", inventoryName));
            }

            List<ChainQueryStruct> ret = new ArrayList<>();

            if (nestConditionNames.size() == 1) {
                FieldChainQuery f = new FieldChainQuery();
                f.self = metadata;
                f.fieldName = nestConditionNames.get(0);
                ret.add(f);
            } else {
                String lastField = nestConditionNames.get(nestConditionNames.size()-1);

                List<String> processedConditionsNames = new ArrayList<>();
                preProcessingNestConditionNames(
                        metadata,
                        nestConditionNames.subList(0, nestConditionNames.size()-1).iterator(),
                        processedConditionsNames
                );

                Iterator<String> iterator = processedConditionsNames.iterator();

                InventoryMetadata self = metadata;
                ExpandQueryMetadata left = null;
                while (iterator.hasNext()) {
                    String expandedQueryName = iterator.next();
                    ExpandQueryMetadata e = self.expandQueries.get(expandedQueryName);
                    if (e == null) {
                        throw new CloudRuntimeException(String.format("no expand query[%s] found on %s", expandedQueryName, self.selfInventoryClass));
                    }
                    ExpandChainQuery em = new ExpandChainQuery();
                    em.selfKey = left == null ? null : left.targetKeyName;
                    em.self = self;
                    em.right = e;
                    self = getInventoryMetadataByName(em.right.targetInventoryClass.getName());
                    left = em.right;
                    ret.add(em);
                }

                ExpandChainQuery last = (ExpandChainQuery) ret.get(ret.size()-1);
                FieldChainQuery f = new FieldChainQuery();
                f.right = last.right;
                f.self = last.self;
                f.fieldName = lastField;
                ret.add(f);
            }

            ret.forEach(ChainQueryStruct::verify);

            return ret;
        }

        private void preProcessingNestConditionNames(InventoryMetadata current, Iterator<String> names, List<String> result) {
            if (!names.hasNext()) {
                return;
            }

            String name = names.next();
            ExpandQueryAliasMetadata alias = current.expandQueryAliases.get(name);
            if (alias != null) {
                List<String> newNames = new ArrayList<>();
                Collections.addAll(newNames, alias.expandQueryText.split("\\."));
                names.forEachRemaining(newNames::add);
                preProcessingNestConditionNames(current, newNames.iterator(), result);
            } else {
                ExpandQueryMetadata expand = current.expandQueries.get(name);

                if (expand == null) {
                    throw new CloudRuntimeException(String.format("invalid nested query condition[%s] on %s," +
                            "the expanded target[%s] have no expanded query[%s]",
                            StringUtils.join(names, "."),
                            current.selfInventoryClass,
                            current.selfInventoryClass,
                            name
                    ));
                }

                current = inventoryMetadata.get(expand.targetInventoryClass.getName());
                if (current == null) {
                    throw new CloudRuntimeException(String.format("unable to find inventory metadata for %s", expand.targetInventoryClass));
                }

                result.add(name);
                preProcessingNestConditionNames(current, names, result);
            }
        }
    }

    public static Class getChildInventoryClassByType(Class parentInventory, String type) {
        Map<String, Class> m = typeFieldToParentInventory.get(parentInventory);
        if (m == null) {
            throw new CloudRuntimeException(String.format("inventory class[%s] has no children inventory classes", parentInventory));
        }

        Class child = m.get(type);
        if (child == null) {
            return parentInventory;
        } else {
            return child;
        }
    }

    private static void fillInventoryMetadata(Class clz,
                                              List<ExpandedQuery> queries, List<ExpandedQueryAlias> aliases,
                                              List<ExpandedQuery> queryForOther, List<ExpandedQueryAlias> aliasForOther) {
        Inventory inventory = (Inventory) clz.getAnnotation(Inventory.class);

        if (inventory == null) {
            throw new CloudRuntimeException(String.format("class[%s] not annotated by @Inventory", clz));
        }

        for (Parent parent : inventory.parent()) {
            Class parentInventory = parent.inventoryClass();
            Map<String, Class> children = typeFieldToParentInventory.computeIfAbsent(parentInventory, x->new HashMap<>());
            children.put(parent.type(), clz);
        }

        InventoryMetadata metadata = inventoryMetadata.computeIfAbsent(clz.getName(), x-> {
            InventoryMetadata m = new InventoryMetadata();
            m.inventoryAnnotation = inventory;
            m.selfInventoryClass = clz;
            m.selfInventoryFieldNames = FieldUtils.getAllFields(clz).stream()
                    .filter(i->!i.isAnnotationPresent(APINoSee.class))
                    .map(Field::getName)
                    .collect(Collectors.toSet());
            return m;
        });

        if (queries != null) {
            queries.forEach(it-> {
                if (it.target() != Object.class && it.target() != clz) {
                    if (queryForOther == null) {
                        throw new CloudRuntimeException(String.format("found %s has an expanded query with target[%s], but queryForOther == null", clz, it.target()));
                    }

                    queryForOther.add(it);
                    return;
                }

                Class targetInventoryClass = it.inventoryClass();
                if (!targetInventoryClass.isAnnotationPresent(Inventory.class)) {
                    throw new CloudRuntimeException(String.format("inventory class[%s] is query expanded by %s but not have @Inventory annotation", targetInventoryClass, clz));
                }

                ExpandQueryMetadata emetadata = new ExpandQueryMetadata();
                emetadata.selfVOClass = inventory.mappingVOClass();
                emetadata.targetVOClass = ((Inventory)targetInventoryClass.getAnnotation(Inventory.class)).mappingVOClass();
                emetadata.targetInventoryClass = it.inventoryClass();
                emetadata.selfKeyName = it.foreignKey();
                emetadata.targetKeyName = it.expandedInventoryKey();
                emetadata.name = it.expandedField();
                emetadata.hidden = it.hidden();

                metadata.expandQueries.put(emetadata.name, emetadata);
            });
        }

        if (aliases != null) {
            aliases.forEach(it -> {
                if (it.target() != Object.class && it.target() != clz) {
                    if (queryForOther == null) {
                        throw new CloudRuntimeException(String.format("found %s has an expanded alias with target[%s], but aliasForOther == null", clz, it.target()));
                    }
                    aliasForOther.add(it);
                    return;
                }

                ExpandQueryAliasMetadata e = new ExpandQueryAliasMetadata();
                e.aliasName = it.alias();
                e.expandQueryText = it.expandedField();
                metadata.expandQueryAliases.put(it.alias(), e);
            });
        }

        FieldUtils.getAllFields(clz).stream().filter(f->f.isAnnotationPresent(Queryable.class)).forEach(f-> {
            if (TypeUtils.isPrimitiveOrWrapper(f.getType())) {
                return;
            }

            if (!Collection.class.isAssignableFrom(f.getType())) {
                return;
            }

            Class gtype =  FieldUtils.getGenericType(f);
            if (gtype == null) {
                return;
            }

            if (TypeUtils.isPrimitiveOrWrapper(gtype)) {
                return;
            }

            Inventory targetInventoryAnnotation = (Inventory) gtype.getAnnotation(Inventory.class);
            if (targetInventoryAnnotation == null) {
                throw new CloudRuntimeException(String.format("field[%s] of inventory[%s] is annotated of @Queryable, however it's generic type[%s] is " +
                        " not annotated by @Inventory", f.getName(), clz, gtype));
            }

            Queryable queryable = f.getAnnotation(Queryable.class);

            // create a expanded query for Queryable field
            ExpandQueryMetadata emetadata = new ExpandQueryMetadata();
            emetadata.selfVOClass = inventory.mappingVOClass();
            emetadata.targetVOClass = targetInventoryAnnotation.mappingVOClass();
            emetadata.targetInventoryClass = gtype;
            emetadata.selfKeyName = EntityMetadata.getPrimaryKeyField(emetadata.selfVOClass).getName();
            emetadata.targetKeyName = queryable.joinColumn().name();
            emetadata.name = f.getName();

            metadata.expandQueries.put(emetadata.name, emetadata);
        });

        inventoryMetadata.put(clz.getName(), metadata);
    }

    @StaticInit
    static void staticInit() {
        List<ExpandedQuery> queryForOther = new ArrayList<>();
        List<ExpandedQueryAlias> aliasForOther = new ArrayList<>();

        BeanUtils.reflections.getTypesAnnotatedWith(Inventory.class).stream().filter(i->i.isAnnotationPresent(Inventory.class))
                .forEach(clz -> {
                    List<ExpandedQuery> expandedQueries = new ArrayList<>();
                    List<ExpandedQueryAlias> expandedQueryAliases = new ArrayList<>();
                    Class tmp = clz;
                    while (tmp != Object.class) {
                        ExpandedQueries queries = (ExpandedQueries) tmp.getAnnotation(ExpandedQueries.class);
                        if (queries != null) {
                            Collections.addAll(expandedQueries, queries.value());
                        }

                        ExpandedQueryAliases aliases = (ExpandedQueryAliases) tmp.getAnnotation(ExpandedQueryAliases.class);
                        if (aliases != null) {
                            Collections.addAll(expandedQueryAliases, aliases.value());
                        }

                        tmp = tmp.getSuperclass();
                    }

                    fillInventoryMetadata(clz,
                            !expandedQueries.isEmpty() ? expandedQueries  : null,
                            !expandedQueryAliases.isEmpty() ? expandedQueryAliases : null,
                            queryForOther, aliasForOther);
                });

        queryForOther.forEach(it -> {
            Class clz = it.target();
            fillInventoryMetadata(clz, asList(it), null,  null, null);
        });

        aliasForOther.forEach(it -> {
            Class clz = it.target();
            fillInventoryMetadata(clz, null, asList(it),  null, null);
        });

        BeanUtils.reflections.getFieldsAnnotatedWith(TypeField.class).forEach(tf -> {
            Field f = inventoryTypeFields.get(tf.getDeclaringClass());
            if (f != null) {
                throw new CloudRuntimeException(String.format("inventory class[%s] has two fields[%s,%s] annotated by @TypeField",
                        f.getDeclaringClass(), tf.getName(), f.getName()));
            }
            inventoryTypeFields.put(tf.getDeclaringClass(), tf);
        });
    }

    public static Field getTypeFieldOfInventoryClass(Class invClass) {
        return inventoryTypeFields.get(invClass);
    }
}
