/******************************************************************************
 * SPDX-License-Identifier: EPL-2.0

import java.util.ArrayList;

/**
 * A deployed web service node in the server view
 * 
 * @author Ludovic Champenois
 *
 */
public class WebServiceNode extends TreeNode{

	DeployedWebServicesNode parent;
	GlassFishServer server = null;
	TreeNode[] modules = null;
	WSDesc app = null;
	public WebServiceNode(DeployedWebServicesNode root, GlassFishServer server, WSDesc app) {
		super(app.getName(), null, root);
		this.server = server;
		this.app = app;
	}
	
	public GlassFishServer getServer(){
		return this.server;
	}
	
	public WSDesc getWSInfo(){
		return this.app;
	}
	@Override
	public IPropertyDescriptor[] getPropertyDescriptors() {
        ArrayList< IPropertyDescriptor > properties = new ArrayList< IPropertyDescriptor >();
        PropertyDescriptor pd;


                pd = new TextPropertyDescriptor( "testurl", "Test URL" );
                properties.add( pd );
                pd = new TextPropertyDescriptor( "name", "name" );
                properties.add( pd );        
                pd = new TextPropertyDescriptor( "wsdlurl", "WSDL URL" );
                properties.add( pd );        
        

        return properties.toArray( new IPropertyDescriptor[ 0 ] );
	}
	@Override
	public Object getPropertyValue(Object id) {
	       if ( id.equals( "testurl" ))
               return app.getTestURL();
	       if ( id.equals( "name" ))
                   return app.getName();
	       if ( id.equals( "wsdlurl" ))
               return app.getWsdlUrl();

     

		
		return null;
	}   	
}