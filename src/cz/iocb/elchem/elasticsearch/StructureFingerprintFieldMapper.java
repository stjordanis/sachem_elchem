package cz.iocb.elchem.elasticsearch;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.TypeParsers;
import org.elasticsearch.index.query.QueryShardContext;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.interfaces.IAtomContainer;
import cz.iocb.elchem.fingerprint.IOCBFingerprint;
import cz.iocb.elchem.lucene.FingerprintTokenStream;
import cz.iocb.elchem.molecule.AromaticityMode;
import cz.iocb.elchem.molecule.BinaryMolecule;
import cz.iocb.elchem.molecule.BinaryMoleculeBuilder;
import cz.iocb.elchem.molecule.InChITools.InChIException;
import cz.iocb.elchem.molecule.MoleculeCreator;



public class StructureFingerprintFieldMapper extends FieldMapper
{
    public static final String CONTENT_TYPE = "structure_fingerprint";


    public static class Defaults
    {
        public static final MappedFieldType FIELD_TYPE = new FieldType();

        static
        {
            FIELD_TYPE.freeze();
        }
    }


    public static class Builder extends FieldMapper.Builder<Builder, StructureFingerprintFieldMapper>
    {
        private AromaticityMode aromaticityMode = AromaticityMode.AUTO;


        public Builder(String name)
        {
            super(name, Defaults.FIELD_TYPE, Defaults.FIELD_TYPE);
            builder = this;
        }


        @Override
        public StructureFingerprintFieldMapper build(BuilderContext context)
        {
            setupFieldType(context);
            return new StructureFingerprintFieldMapper(name, fieldType, defaultFieldType, context.indexSettings(),
                    multiFieldsBuilder.build(this, context), copyTo);
        }


        @Override
        protected void setupFieldType(BuilderContext context)
        {
            super.setupFieldType(context);

            fieldType.setIndexOptions(IndexOptions.NONE);
            fieldType.setHasDocValues(false);
            fieldType.setStored(false);
            ((FieldType) fieldType).aromaticityMode = aromaticityMode;

            defaultFieldType.setIndexOptions(IndexOptions.NONE);
            defaultFieldType.setHasDocValues(false);
            defaultFieldType.setStored(false);
            ((FieldType) defaultFieldType).aromaticityMode = AromaticityMode.AUTO;
        }
    }


    public static class TypeParser implements Mapper.TypeParser
    {
        @Override
        public Mapper.Builder<?, ?> parse(String name, Map<String, Object> node, ParserContext parserContext)
                throws MapperParsingException
        {
            Builder builder = new Builder(name);

            TypeParsers.parseField(builder, name, node, parserContext);

            for(Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();)
            {
                Map.Entry<String, Object> entry = iterator.next();

                if(entry.getKey().equals("aromaticity_mode"))
                {
                    builder.aromaticityMode = AromaticityMode.valueOf(entry.getValue().toString().toUpperCase());
                    iterator.remove();
                }
            }

            return builder;
        }
    }


    public static class FieldType extends MappedFieldType
    {
        private AromaticityMode aromaticityMode = AromaticityMode.AUTO;


        public FieldType()
        {
        }


        protected FieldType(FieldType ref)
        {
            super(ref);

            aromaticityMode = ref.aromaticityMode;
        }


        @Override
        public String typeName()
        {
            return CONTENT_TYPE;
        }


        @Override
        public FieldType clone()
        {
            return new FieldType(this);
        }


        @Override
        public Query existsQuery(QueryShardContext arg0)
        {
            throw new UnsupportedOperationException("Cannot run exists query on [" + CONTENT_TYPE + "]");
        }


        @Override
        public Query termQuery(Object arg0, QueryShardContext arg1)
        {
            throw new IllegalArgumentException("Queries on [" + CONTENT_TYPE + "] fields are not supported");
        }
    }


    protected StructureFingerprintFieldMapper(String simpleName, MappedFieldType fieldType,
            MappedFieldType defaultFieldType, Settings indexSettings, MultiFields multiFields, CopyTo copyTo)
    {
        super(simpleName, fieldType, defaultFieldType, indexSettings, multiFields, copyTo);
    }


    @Override
    protected String contentType()
    {
        return CONTENT_TYPE;
    }


    @Override
    protected void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException
    {
        super.doXContentBody(builder, includeDefaults, params);

        if(includeDefaults || ((FieldType) fieldType).aromaticityMode != ((FieldType) defaultFieldType).aromaticityMode)
            builder.field("aromaticity_mode", ((FieldType) fieldType).aromaticityMode.name());
    }


    @Override
    protected void parseCreateField(ParseContext context, List<IndexableField> fields) throws IOException
    {
        AromaticityMode aromaticityMode = ((FieldType) fieldType).aromaticityMode;

        String data = context.externalValueSet() ? context.externalValue().toString() : context.parser().textOrNull();

        if(data == null)
            return;

        try
        {
            byte[] binary;

            try
            {
                IAtomContainer container = MoleculeCreator.translateMolecule(data, aromaticityMode, true);
                binary = BinaryMoleculeBuilder.asBytes(container, true);
            }
            catch(InChIException e)
            {
                IAtomContainer container = MoleculeCreator.translateMolecule(data, aromaticityMode, false);
                binary = BinaryMoleculeBuilder.asBytes(container, true);
            }

            String name = fieldType().name();
            BinaryMolecule molecule = new BinaryMolecule(binary);
            Set<Integer> fp = IOCBFingerprint.getSubstructureFingerprint(molecule);

            fields.add(new StoredField(name, binary));
            fields.add(new BinaryDocValuesField(name, new BytesRef(binary)));
            fields.add(new TextField(name, new FingerprintTokenStream(fp)));
        }
        catch(CDKException e)
        {
            throw new IOException(e);
        }
    }
}
