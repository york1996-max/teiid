/*
 * Copyright Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags and
 * the COPYRIGHT.txt file distributed with this work.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.teiid.translator.file;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.resource.ResourceException;
import javax.resource.cci.Connection;
import javax.resource.cci.ConnectionFactory;

import org.jboss.vfs.VFS;
import org.jboss.vfs.VirtualFile;
import org.teiid.connector.DataPlugin;
import org.teiid.core.BundleUtil;
import org.teiid.core.TeiidRuntimeException;
import org.teiid.core.types.BlobImpl;
import org.teiid.core.types.BlobType;
import org.teiid.core.types.ClobImpl;
import org.teiid.core.types.ClobType;
import org.teiid.core.types.InputStreamFactory;
import org.teiid.core.types.InputStreamFactory.FileInputStreamFactory;
import org.teiid.core.util.ObjectConverterUtil;
import org.teiid.core.util.ReaderInputStream;
import org.teiid.file.VirtualFileConnection;
import org.teiid.language.Call;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.metadata.MetadataFactory;
import org.teiid.metadata.Procedure;
import org.teiid.metadata.ProcedureParameter;
import org.teiid.metadata.ProcedureParameter.Type;
import org.teiid.metadata.RuntimeMetadata;
import org.teiid.translator.DataNotAvailableException;
import org.teiid.translator.ExecutionContext;
import org.teiid.translator.ExecutionFactory;
import org.teiid.translator.FileConnection;
import org.teiid.translator.ProcedureExecution;
import org.teiid.translator.Translator;
import org.teiid.translator.TranslatorException;
import org.teiid.translator.TranslatorProperty;
import org.teiid.translator.TypeFacility;
import org.teiid.util.CharsetUtils;

@Translator(name="file", description="File Translator, reads contents of files or writes to them")
public class FileExecutionFactory extends ExecutionFactory<ConnectionFactory, Connection> {
	
	private final class FileProcedureExecution implements ProcedureExecution {
		private final Call command;
		private final FileConnection fc;
		private File[] files = null;
		boolean isText = false;
		private int index;

		private FileProcedureExecution(Call command, FileConnection fc) {
			this.command = command;
			this.fc = fc;
		}

		@Override
		public void execute() throws TranslatorException {
			String path = (String)command.getArguments().get(0).getArgumentValue().getValue();
			try {
				files = FileConnection.Util.getFiles(path, fc, exceptionIfFileNotFound);
			} catch (ResourceException e) {
				throw new TranslatorException(e);
			}
			LogManager.logDetail(LogConstants.CTX_CONNECTOR, "Getting", files != null ? files.length : 0, "file(s)"); //$NON-NLS-1$ //$NON-NLS-2$
			String name = command.getProcedureName();
			if (name.equalsIgnoreCase(GETTEXTFILES)) {
				isText = true;
			} else if (!name.equalsIgnoreCase(GETFILES)) {
				throw new TeiidRuntimeException("Unknown procedure name " + name); //$NON-NLS-1$
			}
		}

		@Override
		public void close() {
			
		}

		@Override
		public void cancel() throws TranslatorException {
			
		}

		@Override
		public List<?> next() throws TranslatorException, DataNotAvailableException {
			if (files == null || index >= files.length) {
				return null;
			}
			ArrayList<Object> result = new ArrayList<Object>(2);
			final File file = files[index++];
			LogManager.logDetail(LogConstants.CTX_CONNECTOR, "Getting", file); //$NON-NLS-1$
			FileInputStreamFactory isf = new FileInputStreamFactory(file);
			isf.setLength(file.length());
			Object value = null;
			if (isText) {
				ClobImpl clob = new ClobImpl(isf, -1);
				clob.setCharset(encoding);
				value = new ClobType(clob);
			} else {
				value = new BlobType(new BlobImpl(isf));
			}
			result.add(value);
			result.add(file.getName());
			if (command.getMetadataObject().getResultSet().getColumns().size() > 2) {
    			result.add(new Timestamp(file.lastModified()));
                try {
                    Map<String, Object> attributes = Files.readAttributes(file.toPath(), "size,creationTime"); //$NON-NLS-1$
                    result.add(new Timestamp(((FileTime)attributes.get("creationTime")).toMillis())); //$NON-NLS-1$
                    result.add(attributes.get("size")); //$NON-NLS-1$
                } catch (IOException e) {
                    throw new TranslatorException(e);
                }
			}
			return result;
		}

		@Override
		public List<?> getOutputParameterValues() throws TranslatorException {
			return Collections.emptyList();
		}
	}
	
    private final class VirtualFileProcedureExecution implements ProcedureExecution {
        
        private final Call command;
        private final VirtualFileConnection conn;
        private VirtualFile[] files = null;
        boolean isText = false;
        private int index;
        
        private VirtualFileProcedureExecution(Call command, VirtualFileConnection conn) {
            this.command = command;
            this.conn = conn;
        }

        @Override
        public void execute() throws TranslatorException {
            String filePath = (String)command.getArguments().get(0).getArgumentValue().getValue();
            if(this.command.getProcedureName().equalsIgnoreCase(SAVEFILE)){
                Object file = command.getArguments().get(1).getArgumentValue().getValue();
                if (file == null || filePath == null) {
                    throw new TranslatorException(UTIL.getString("non_null")); //$NON-NLS-1$
                }
                LogManager.logDetail(LogConstants.CTX_CONNECTOR, "Saving", filePath); //$NON-NLS-1$
                InputStream is = null;
                try {
                    if (file instanceof SQLXML){
                        is = ((SQLXML)file).getBinaryStream();
                    } else if (file instanceof Clob) {
                        is = new ReaderInputStream(((Clob)file).getCharacterStream(), encoding);
                    } else if (file instanceof Blob) {
                        is = ((Blob)file).getBinaryStream();
                    } else {
                        throw new TranslatorException(UTIL.getString("unknown_type")); //$NON-NLS-1$
                    }
                    this.conn.add(is, VFS.getChild(filePath));
                } catch (SQLException | ResourceException e) {
                    throw new TranslatorException(e, UTIL.getString("error_writing")); //$NON-NLS-1$
                }
            } else  if(this.command.getProcedureName().equalsIgnoreCase(DELETEFILE)) {
                if (filePath == null) {
                    throw new TranslatorException(UTIL.getString("non_null")); //$NON-NLS-1$
                }
                LogManager.logDetail(LogConstants.CTX_CONNECTOR, "Deleting", filePath); //$NON-NLS-1$
                try {
                    if(!this.conn.remove(filePath)) {
                        throw new TranslatorException(UTIL.getString("error_deleting")); //$NON-NLS-1$
                    }
                } catch (ResourceException e) {
                    throw new TranslatorException(UTIL.getString("error_deleting")); //$NON-NLS-1$
                }
            } else {
                try {
                    this.files = this.conn.getFiles(filePath);
                } catch (ResourceException e) {
                    throw new TranslatorException(e);
                }
                LogManager.logDetail(LogConstants.CTX_CONNECTOR, "Getting", files != null ? files.length : 0, "file(s)"); //$NON-NLS-1$ //$NON-NLS-2$
                String name = command.getProcedureName();
                if(name.equalsIgnoreCase(GETTEXTFILES)) {
                    this.isText = true;
                } else if (!name.equalsIgnoreCase(GETFILES)) {
                    throw new TeiidRuntimeException("Unknown procedure name " + name); //$NON-NLS-1$
                }
            }
        }
        
        @Override
        public List<?> next() throws TranslatorException, DataNotAvailableException {
            if(this.command.getProcedureName().equalsIgnoreCase(SAVEFILE) || this.command.getProcedureName().equalsIgnoreCase(DELETEFILE)){
                return null;
            }
            ArrayList<Object> result = new ArrayList<>(2);
            final VirtualFile file = files[index++];
            LogManager.logDetail(LogConstants.CTX_CONNECTOR, "Getting", file.getName()); //$NON-NLS-1$
            InputStreamFactory isf = new InputStreamFactory() {
                @Override
                public InputStream getInputStream() throws IOException {
                    return file.openStream(); // vfs file always can open as stream
                }  
            };
            Object value = null;
            if (isText) {
                ClobImpl clob = new ClobImpl(isf, -1);
                clob.setCharset(encoding);
                value = new ClobType(clob);
            } else {
                value = new BlobType(new BlobImpl(isf));
            }
            result.add(value);
            result.add(file.getName());
            if (command.getMetadataObject().getResultSet().getColumns().size() > 2) {
                Timestamp lastModified = new Timestamp(file.getLastModified());
                result.add(lastModified);
                result.add(lastModified);
                result.add(file.getSize());
            }
            return result;
        }

        @Override
        public void close() {            
        }

        @Override
        public void cancel() throws TranslatorException {            
        }

        @Override
        public List<?> getOutputParameterValues() throws TranslatorException {
            return Collections.emptyList();
        }
    }

	public static BundleUtil UTIL = BundleUtil.getBundleUtil(FileExecutionFactory.class);
	
	public static final String GETTEXTFILES = "getTextFiles"; //$NON-NLS-1$
	public static final String GETFILES = "getFiles"; //$NON-NLS-1$
	public static final String SAVEFILE = "saveFile"; //$NON-NLS-1$
	public static final String DELETEFILE = "deleteFile"; //$NON-NLS-1$
	
	private Charset encoding = Charset.defaultCharset();
	private boolean exceptionIfFileNotFound = true;
	
	public FileExecutionFactory() {
		setTransactionSupport(TransactionSupport.NONE);
		setSourceRequiredForMetadata(false);
	}
	
	@TranslatorProperty(display="File Encoding",advanced=true)
	public String getEncoding() {
		return encoding.name();
	}
	
	public void setEncoding(String encoding) {
		this.encoding = CharsetUtils.getCharset(encoding);
	}
	
	@TranslatorProperty(display="Exception if file not found",advanced=true)
	public boolean isExceptionIfFileNotFound() {
		return exceptionIfFileNotFound;
	}
	
	public void setExceptionIfFileNotFound(boolean exceptionIfFileNotFound) {
		this.exceptionIfFileNotFound = exceptionIfFileNotFound;
	}
	
	//@Override
	public ProcedureExecution createProcedureExecution(final Call command,
			final ExecutionContext executionContext, final RuntimeMetadata metadata,
			final Connection conn) throws TranslatorException {
	    if(conn instanceof VirtualFileConnection) {
	        return new VirtualFileProcedureExecution(command, (VirtualFileConnection) conn);
	    }
	    final FileConnection fc = (FileConnection) conn;
		if (command.getProcedureName().equalsIgnoreCase(SAVEFILE)) {
			return new ProcedureExecution() {
				
				@Override
				public void execute() throws TranslatorException {
					String filePath = (String)command.getArguments().get(0).getArgumentValue().getValue();
					Object file = command.getArguments().get(1).getArgumentValue().getValue();
					if (file == null || filePath == null) {
						throw new TranslatorException(UTIL.getString("non_null")); //$NON-NLS-1$
					}
					LogManager.logDetail(LogConstants.CTX_CONNECTOR, "Saving", filePath); //$NON-NLS-1$
					InputStream is = null;
					try {
						if (file instanceof SQLXML) {
							is = ((SQLXML)file).getBinaryStream();
						} else if (file instanceof Clob) {
							is = new ReaderInputStream(((Clob)file).getCharacterStream(), encoding);
						} else if (file instanceof Blob) {
							is = ((Blob)file).getBinaryStream();
						} else {
							throw new TranslatorException(UTIL.getString("unknown_type")); //$NON-NLS-1$
						}
					
						ObjectConverterUtil.write(is, fc.getFile(filePath));
					} catch (IOException e) {
						throw new TranslatorException(e, UTIL.getString("error_writing")); //$NON-NLS-1$
					} catch (SQLException e) {
						throw new TranslatorException(e, UTIL.getString("error_writing")); //$NON-NLS-1$
					} catch (ResourceException e) {
						throw new TranslatorException(e, UTIL.getString("error_writing")); //$NON-NLS-1$
					}
				}
				
				@Override
				public void close() {
				}
				
				@Override
				public void cancel() throws TranslatorException {
				}
				
				@Override
				public List<?> next() throws TranslatorException, DataNotAvailableException {
					return null;
				}
				
				@Override
				public List<?> getOutputParameterValues() throws TranslatorException {
					return Collections.emptyList();
				}
			};
		} else if (command.getProcedureName().equalsIgnoreCase(DELETEFILE)) {
			return new ProcedureExecution() {
				
				@Override
				public void execute() throws TranslatorException {
					String filePath = (String)command.getArguments().get(0).getArgumentValue().getValue();
					
					if ( filePath == null) {
						throw new TranslatorException(UTIL.getString("non_null")); //$NON-NLS-1$
					}
					LogManager.logDetail(LogConstants.CTX_CONNECTOR, "Deleting", filePath); //$NON-NLS-1$
					
					try {
						File f = fc.getFile(filePath);
						if (!f.exists()) {
							if (exceptionIfFileNotFound) {
								throw new TranslatorException(DataPlugin.Util.gs("file_not_found", filePath)); //$NON-NLS-1$
							}
						} else if(!f.delete()){
							throw new TranslatorException(UTIL.getString("error_deleting")); //$NON-NLS-1$
						}
						
					} catch (ResourceException e) {
						throw new TranslatorException(e, UTIL.getString("error_deleting")); //$NON-NLS-1$
					}
				}
				
				@Override
				public void close() {
				}
				
				@Override
				public void cancel() throws TranslatorException {
				}
				
				@Override
				public List<?> next() throws TranslatorException, DataNotAvailableException {
					return null;
				}
				
				@Override
				public List<?> getOutputParameterValues() throws TranslatorException {
					return Collections.emptyList();
				}
			};
		}
		return new FileProcedureExecution(command, fc);
	}

	@Override
	public void getMetadata(MetadataFactory metadataFactory, Connection connection) throws TranslatorException {
		Procedure p = metadataFactory.addProcedure(GETTEXTFILES);
		p.setAnnotation("Returns text files that match the given path and pattern as CLOBs"); //$NON-NLS-1$
		ProcedureParameter param = metadataFactory.addProcedureParameter("pathAndPattern", TypeFacility.RUNTIME_NAMES.STRING, Type.In, p); //$NON-NLS-1$
		param.setAnnotation("The path and pattern of what files to return.  Currently the only pattern supported is *.<ext>, which returns only the files matching the given extension at the given path."); //$NON-NLS-1$
		metadataFactory.addProcedureResultSetColumn("file", TypeFacility.RUNTIME_NAMES.CLOB, p); //$NON-NLS-1$
		metadataFactory.addProcedureResultSetColumn("filePath", TypeFacility.RUNTIME_NAMES.STRING, p); //$NON-NLS-1$
		metadataFactory.addProcedureResultSetColumn("lastModified", TypeFacility.RUNTIME_NAMES.TIMESTAMP, p); //$NON-NLS-1$
        metadataFactory.addProcedureResultSetColumn("created", TypeFacility.RUNTIME_NAMES.TIMESTAMP, p); //$NON-NLS-1$
        metadataFactory.addProcedureResultSetColumn("size", TypeFacility.RUNTIME_NAMES.LONG, p); //$NON-NLS-1$
		
		Procedure p1 = metadataFactory.addProcedure(GETFILES);
		p1.setAnnotation("Returns files that match the given path and pattern as BLOBs"); //$NON-NLS-1$
		param = metadataFactory.addProcedureParameter("pathAndPattern", TypeFacility.RUNTIME_NAMES.STRING, Type.In, p1); //$NON-NLS-1$
		param.setAnnotation("The path and pattern of what files to return.  Currently the only pattern supported is *.<ext>, which returns only the files matching the given extension at the given path."); //$NON-NLS-1$
		metadataFactory.addProcedureResultSetColumn("file", TypeFacility.RUNTIME_NAMES.BLOB, p1); //$NON-NLS-1$
		metadataFactory.addProcedureResultSetColumn("filePath", TypeFacility.RUNTIME_NAMES.STRING, p1); //$NON-NLS-1$
		metadataFactory.addProcedureResultSetColumn("lastModified", TypeFacility.RUNTIME_NAMES.TIMESTAMP, p1); //$NON-NLS-1$
		metadataFactory.addProcedureResultSetColumn("created", TypeFacility.RUNTIME_NAMES.TIMESTAMP, p1); //$NON-NLS-1$
		metadataFactory.addProcedureResultSetColumn("size", TypeFacility.RUNTIME_NAMES.LONG, p1); //$NON-NLS-1$
		
		Procedure p2 = metadataFactory.addProcedure(SAVEFILE);
		p2.setAnnotation("Saves the given value to the given path.  Any existing file will be overriden."); //$NON-NLS-1$
		metadataFactory.addProcedureParameter("filePath", TypeFacility.RUNTIME_NAMES.STRING, Type.In, p2); //$NON-NLS-1$
		param = metadataFactory.addProcedureParameter("file", TypeFacility.RUNTIME_NAMES.OBJECT, Type.In, p2); //$NON-NLS-1$
		param.setAnnotation("The contents to save.  Can be one of CLOB, BLOB, or XML"); //$NON-NLS-1$
		
		Procedure p3 = metadataFactory.addProcedure(DELETEFILE);
		p3.setAnnotation("Delete the given file path. "); //$NON-NLS-1$
		metadataFactory.addProcedureParameter("filePath", TypeFacility.RUNTIME_NAMES.STRING, Type.In, p3); //$NON-NLS-1$	
	} 
	
	@Override
	public boolean areLobsUsableAfterClose() {
		return true;
	}
	
}
