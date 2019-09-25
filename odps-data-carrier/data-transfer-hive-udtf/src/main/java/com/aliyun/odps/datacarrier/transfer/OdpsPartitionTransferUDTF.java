package com.aliyun.odps.datacarrier.transfer;

import com.aliyun.odps.Odps;
import com.aliyun.odps.PartitionSpec;
import com.aliyun.odps.TableSchema;
import com.aliyun.odps.account.AliyunAccount;
import com.aliyun.odps.data.Record;
import com.aliyun.odps.data.RecordWriter;
import com.aliyun.odps.datacarrier.transfer.converter.HiveObjectConverter;
import com.aliyun.odps.tunnel.TableTunnel;
import com.aliyun.odps.tunnel.TableTunnel.UploadSession;
import com.aliyun.odps.tunnel.TunnelException;
import com.aliyun.odps.tunnel.io.TunnelBufferedWriter;
import com.aliyun.odps.type.TypeInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDTF;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;

public class OdpsPartitionTransferUDTF extends GenericUDTF {

  ObjectInspector[] objectInspectors;
  Odps odps;
  TableTunnel tunnel;
  UploadSession uploadSession;
  RecordWriter recordWriter;
  String currentOdpsTableName;
  List<String> odpsColumnNames;
  String currentOdpsPartitionSpec;
  TableSchema schema;

  @Override
  public StructObjectInspector initialize(ObjectInspector[] args) throws UDFArgumentException {
    objectInspectors = args;
    // This UDTF doesn't output anything
    return ObjectInspectorFactory.getStandardStructObjectInspector(new ArrayList<String>(),
        new ArrayList<ObjectInspector>());
  }

  @Override
  public void process(Object[] args) throws HiveException {
    try {
      if(odps == null) {
        OdpsConfig odpsConfig = new OdpsConfig("odps_config.ini");
        AliyunAccount account = new AliyunAccount(odpsConfig.getAccessId(), odpsConfig.getAccessKey());
        odps = new Odps(account);
        odps.setDefaultProject(odpsConfig.getProjectName());
        odps.setEndpoint(odpsConfig.getOdpsEndpoint());
        tunnel = new TableTunnel(odps);
        if (odpsConfig.getTunnelEndpoint() != null) {
          tunnel.setEndpoint(odpsConfig.getTunnelEndpoint());
        }
      }

      if (currentOdpsTableName == null) {
        StringObjectInspector soi0 = (StringObjectInspector) objectInspectors[0];
        StringObjectInspector soi1 = (StringObjectInspector) objectInspectors[1];
        StringObjectInspector soi2 = (StringObjectInspector) objectInspectors[2];

        currentOdpsTableName = soi0.getPrimitiveJavaObject(args[0]).trim();
        schema = odps.tables().get(currentOdpsTableName).getSchema();

        currentOdpsPartitionSpec = soi1.getPrimitiveJavaObject(args[1]).trim();
        uploadSession = tunnel.createUploadSession(odps.getDefaultProject(),
            currentOdpsTableName, new PartitionSpec(currentOdpsPartitionSpec));
        recordWriter = uploadSession.openBufferedWriter(true);
        ((TunnelBufferedWriter) recordWriter).setBufferSize(64 * 1024 * 1024);

        String odpsColumnNameString = soi2.getPrimitiveJavaObject(args[2]).trim();
        odpsColumnNames = new ArrayList<>();
        if (!odpsColumnNameString.isEmpty()) {
          odpsColumnNames.addAll(Arrays.asList(trimAll(odpsColumnNameString.split(","))));
        }
      }

      List<Object> hiveColumnValues = new ArrayList<>();
      for (int i = 0; i < odpsColumnNames.size(); i++) {
        hiveColumnValues.add(args[i + 3]);
      }

      Record record = uploadSession.newRecord();
      for (int i = 0; i < odpsColumnNames.size(); i++) {
        String odpsColumnName = odpsColumnNames.get(i);
        Object value = hiveColumnValues.get(i);
        if (value == null) {
          continue;
        }

        // Handle data types
        ObjectInspector objectInspector = objectInspectors[i + 3];
        TypeInfo typeInfo = schema.getColumn(odpsColumnName).getTypeInfo();

        record.set(odpsColumnName, HiveObjectConverter.convert(objectInspector, value, typeInfo));
      }

      recordWriter.write(record);
    } catch (Exception e) {
      e.printStackTrace();
      throw new HiveException(e);
    }
  }

  private String[] trimAll(String[] array) {
    for (int i = 0; i < array.length; i++) {
      array[i] = array[i].trim();
    }
    return array;
  }

  @Override
  public void close() throws HiveException {
    if (uploadSession != null) {
      try {
        recordWriter.close();
        uploadSession.commit();
      } catch (IOException | TunnelException e) {
        e.printStackTrace();
        throw new HiveException(e);
      }
    }
  }
}