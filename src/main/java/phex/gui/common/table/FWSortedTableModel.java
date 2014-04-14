/*
 *  PHEX - The pure-java Gnutella-servent.
 *  Copyright (C) 2001 - 2008 Phex Development Group
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 * 
 *  --- SVN Information ---
 *  $Id: FWSortedTableModel.java 4360 2009-01-16 09:08:57Z gregork $
 */

package phex.gui.common.table;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.*;
import java.util.List;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.*;

import org.apache.commons.collections.comparators.ComparableComparator;
import org.apache.commons.collections.comparators.ReverseComparator;

import phex.common.log.NLogger;

/**
 * TableSorter is a decorator for TableModels; adding sorting
 * functionality to a supplied TableModel. TableSorter does
 * not store or copy the data in its TableModel; instead it maintains
 * a map from the row indexes of the view to the row indexes of the
 * model. As requests are made of the sorter (like getValueAt(row, col))
 * they are passed to the underlying model after the row numbers
 * have been translated via the internal mapping array. This way,
 * the TableSorter appears to hold another copy of the table
 * with the rows in a different order.
 * <p/>
 * TableSorter registers itself as a listener to the underlying model,
 * just as the JTable itself would. Events received from the model
 * are examined, sometimes manipulated (typically widened), and then
 * passed on to the TableSorter's listeners (typically the JTable).
 * If a change to the model has invalidated the order of TableSorter's
 * rows, a note of this is made and the sorter will resort the
 * rows the next time a value is requested.
 * <p/>
 * When the tableHeader property is set, either by using the
 * setTableHeader() method or the two argument constructor, the
 * table header may be used as a complete UI for TableSorter.
 * The default renderer of the tableHeader is decorated with a renderer
 * that indicates the sorting status of each column. In addition,
 * a mouse listener is installed with the following behavior:
 * <ul>
 * <li>
 * Mouse-click: Clears the sorting status of all other columns
 * and advances the sorting status of that column through three
 * values: {NOT_SORTED, ASCENDING, DESCENDING} (then back to
 * NOT_SORTED again).
 * <li>
 * SHIFT-mouse-click: Clears the sorting status of all other columns
 * and cycles the sorting status of the column through the same
 * three values, in the opposite order: {NOT_SORTED, DESCENDING, ASCENDING}.
 * <li>
 * CONTROL-mouse-click and CONTROL-SHIFT-mouse-click: as above except
 * that the changes to the column do not cancel the statuses of columns
 * that are already sorting - giving a way to initiate a compound
 * sort.
 * </ul>
 * <p/>
 * This is a long overdue rewrite of a class of the same name that
 * first appeared in the swing table demos in 1997.
 * 
 * @author Philip Milne
 * @author Brendon McLean 
 * @author Dan van Enckevort
 * @author Parwinder Sekhon
 * @version 2.0 02/27/04
 */

public class FWSortedTableModel extends AbstractTableModel
{
    public static final int DESCENDING = -1;
    public static final int NOT_SORTED = 0;
    public static final int ASCENDING = 1;
    private static Directive EMPTY_DIRECTIVE = new Directive(-1, NOT_SORTED);
    
    /**
     * The model that will be sorted.
     */
    protected FWSortableTableModel tableModel;

    private Row[] viewToModel;
    private int[] modelToView;

    private JTableHeader tableHeader;
    private MouseListener mouseListener;
    private TableModelListener tableModelListener;
    private Map columnComparators = new HashMap();
    private List sortingColumns = new ArrayList();
    
    private FWTable table;

    public FWSortedTableModel()
    {
        this.mouseListener = new MouseHandler();
        this.tableModelListener = new TableModelHandler();
    }

    public FWSortedTableModel( FWSortableTableModel tableModel )
    {
        this();
        setTableModel(tableModel);
    }
    public void setTable( FWTable table )
    {
        this.table = table;
    }

    public FWSortedTableModel( FWSortableTableModel tableModel, JTableHeader tableHeader )
    {
        this();
        setTableHeader(tableHeader);
        setTableModel(tableModel);
    }

    private void clearSortingState()
    {
        viewToModel = null;
        modelToView = null;
    }

    public FWSortableTableModel getTableModel()
    {
        return tableModel;
    }

    public void setTableModel( FWSortableTableModel tableModel )
    {
        if (this.tableModel != null)
        {
            this.tableModel.removeTableModelListener(tableModelListener);
        }

        this.tableModel = tableModel;
        if (this.tableModel != null) 
        {
            this.tableModel.addTableModelListener(tableModelListener);
        }

        clearSortingState();
        fireTableStructureChanged();
    }

    public JTableHeader getTableHeader()
    {
        return tableHeader;
    }

    public void setTableHeader( JTableHeader tableHeader )
    {
        if (this.tableHeader != null)
        {
            this.tableHeader.removeMouseListener(mouseListener);
            TableCellRenderer defaultRenderer = this.tableHeader.getDefaultRenderer();
            if (defaultRenderer instanceof SortableHeaderRenderer) 
            {
                this.tableHeader.setDefaultRenderer(((SortableHeaderRenderer) defaultRenderer).tableCellRenderer);
            }
        }
        this.tableHeader = tableHeader;
        if (this.tableHeader != null)
        {
            this.tableHeader.addMouseListener(mouseListener);
            this.tableHeader.setDefaultRenderer(
                new SortableHeaderRenderer(this.tableHeader.getDefaultRenderer()));
        }
    }

    public boolean isSorting()
    {
        return sortingColumns.size() != 0;
    }

    private Directive getDirective(int column)
    {
        for (int i = 0; i < sortingColumns.size(); i++)
        {
            Directive directive = (Directive)sortingColumns.get(i);
            if (directive.column == column) {
                return directive;
            }
        }
        return EMPTY_DIRECTIVE;
    }

    public int getSortingStatus(int column)
    {
        return getDirective(column).direction;
    }

    private void sortingStatusChanged()
    {
        clearSortingState();
        fireTableDataChanged();
        if (tableHeader != null) {
            tableHeader.repaint();
        }
    }

    public void setSortingStatus(int column, int status)
    {
        Directive directive = getDirective(column);
        if (directive != EMPTY_DIRECTIVE) {
            sortingColumns.remove(directive);
        }
        if (status != NOT_SORTED) {
            sortingColumns.add(new Directive(column, status));
        }
        sortingStatusChanged();
    }

    protected Icon getHeaderRendererIcon(int column, int size)
    {
        Directive directive = getDirective(column);
        if (directive == EMPTY_DIRECTIVE) {
            return null;
        }
        return new Arrow(directive.direction == DESCENDING, size, sortingColumns.indexOf(directive));
    }

    public void cancelSorting()
    {
        sortingColumns.clear();
        sortingStatusChanged();
    }

    public void setColumnComparator(Class type, Comparator comparator)
    {
        if (comparator == null)
        {
            columnComparators.remove(type);
        }
        else
        {
            columnComparators.put(type, comparator);
        }
    }

    protected Comparator getComparator(int column)
    {
        Comparator comparator = tableModel.getColumnComparator( column );
        if (comparator != null)
        {
            return comparator;
        }
        
        Class columnType = tableModel.getColumnClass(column);
        comparator = (Comparator)columnComparators.get(columnType);
        if (comparator != null)
        {
            return comparator;
        }
        if (Comparable.class.isAssignableFrom(columnType))
        {
            return ComparableComparator.getInstance();
        }
        return LEXICAL_COMPARATOR;
    }

    private Row[] getViewToModel()
    {
        if (viewToModel == null)
        {
            int tableModelRowCount = tableModel.getRowCount();
            viewToModel = new Row[tableModelRowCount];
            for (int row = 0; row < tableModelRowCount; row++)
            {
                viewToModel[row] = new Row(row);
            }

            if (isSorting())
            {
                Arrays.sort(viewToModel);
            }
        }
        return viewToModel;
    }

    public int getModelIndex(int viewIndex)
    {
        try
        {
            Row[] viewRows = getViewToModel();
            if ( viewIndex < 0 || viewIndex >= viewRows.length ||
                viewRows[viewIndex] == null )
            {
                return -1;
            }
            return viewRows[viewIndex].modelIndex;
        }
        catch ( RuntimeException exp )
        {
            NLogger.error( FWSortedTableModel.class, 
                tableModel.toString() + " " + viewIndex, exp );
            throw exp;
        }
    }

    private int[] getModelToView()
    {
        if (modelToView == null)
        {
            int n = getViewToModel().length;
            modelToView = new int[n];
            for (int i = 0; i < n; i++)
            {
                modelToView[getModelIndex(i)] = i;
            }
        }
        return modelToView;
    }
    
    public int getViewIndex( int modelIndex )
    {
        if ( modelIndex < 0 || modelIndex >= getRowCount() )
        {
            return -1;
        }
        return getModelToView()[ modelIndex ];
    }

    // TableModel interface methods 
    public int getRowCount()
    {
        return (tableModel == null) ? 0 : tableModel.getRowCount();
    }

    public int getColumnCount()
    {
        return (tableModel == null) ? 0 : tableModel.getColumnCount();
    }

    public String getColumnName(int column)
    {
        return tableModel.getColumnName(column);
    }

    public Class getColumnClass(int column)
    {
        return tableModel.getColumnClass(column);
    }

    public boolean isCellEditable(int row, int column)
    {
        return tableModel.isCellEditable(getModelIndex(row), column);
    }

    public Object getValueAt(int row, int column)
    {
        int modelIndex;
        try
        {
            modelIndex = getModelIndex(row);
        }
        catch ( RuntimeException exp )
        {
            // TODO help to identify problems in FWSortedTableModel$Row
            return "";
        }
        if ( modelIndex == -1 )
        {
            return "";
        }
        return tableModel.getValueAt(modelIndex, column);
    }

    public void setValueAt(Object aValue, int row, int column)
    {
        tableModel.setValueAt(aValue, getModelIndex(row), column);
    }

    // Helper classes
    
    private class Row implements Comparable
    {
        private int modelIndex;

        public Row(int index)
        {
            this.modelIndex = index;
        }

        public int compareTo(Object o)
        {
            int row1 = modelIndex;
            int row2 = ((Row) o).modelIndex;

            for (Iterator it = sortingColumns.iterator(); it.hasNext();)
            {
                Directive directive = (Directive) it.next();
                int column = directive.column;
                Object o1 = tableModel.getComparableValueAt(row1, column);
                Object o2 = tableModel.getComparableValueAt(row2, column);

                int comparison = 0;
                // Define null less than everything, except null.
                if (o1 == null && o2 == null)
                {
                    comparison = 0;
                }
                else if (o1 == null)
                {
                    comparison = -1;
                }
                else if (o2 == null)
                {
                    comparison = 1;
                }
                else
                {
                    try
                    {
                        comparison = getComparator(column).compare(o1, o2);
                    }
                    catch ( RuntimeException exp )
                    {// TODO this is here to help debug...
                        NLogger.error( FWSortedTableModel.class,  
                            "In " + tableModel.toString() + " while comparing column " + column 
                            + " Data: " + o1 + " - " + o2, exp );
                        throw exp;
                    }
                }
                if (comparison != 0)
                {
                    return directive.direction == DESCENDING ? -comparison : comparison;
                }
            }
            return 0;
        }
    }

    private class TableModelHandler implements TableModelListener
    {
        public void tableChanged(TableModelEvent e)
        {
            // If we're not sorting by anything, just pass the event along.             
            if (!isSorting())
            {
                clearSortingState();
                fireTableChanged(e);
                return;
            }
                
            // If the table structure has changed, cancel the sorting; the             
            // sorting columns may have been either moved or deleted from             
            // the model. 
            if (e.getFirstRow() == TableModelEvent.HEADER_ROW)
            {
                cancelSorting();
                fireTableChanged(e);
                return;
            }

            // We can map a cell event through to the view without widening             
            // when the following conditions apply: 
            // 
            // a) all the changes are on one row (e.getFirstRow() == e.getLastRow()) and, 
            // b) all the changes are in one column (column != TableModelEvent.ALL_COLUMNS) and,
            // c) we are not sorting on that column (getSortingStatus(column) == NOT_SORTED) and, 
            // d) a reverse lookup will not trigger a sort (modelToView != null)
            //
            // Note: INSERT and DELETE events fail this test as they have column == ALL_COLUMNS.
            // 
            // The last check, for (modelToView != null) is to see if modelToView 
            // is already allocated. If we don't do this check; sorting can become 
            // a performance bottleneck for applications where cells  
            // change rapidly in different parts of the table. If cells 
            // change alternately in the sorting column and then outside of             
            // it this class can end up re-sorting on alternate cell updates - 
            // which can be a performance problem for large tables. The last 
            // clause avoids this problem. 
            int column = e.getColumn();
            if (e.getFirstRow() == e.getLastRow()
                    && column != TableModelEvent.ALL_COLUMNS
                    && getSortingStatus(column) == NOT_SORTED
                    && modelToView != null)
            {
                int viewIndex = getModelToView()[e.getFirstRow()];
                fireTableChanged(new TableModelEvent(FWSortedTableModel.this, 
                                                     viewIndex, viewIndex, 
                                                     column, e.getType()));
                return;
            }

            // TODO3 try to find a improved table selection concept while sorting.
//            System.out.println("----------------------------");
//            int[] modelRows = null;
//            if ( table != null )
//            {
//                int[] viewRows = table.getSelectedRows();
//                
//                System.out.println("selection view");
//                for (int i = 0; i < viewRows.length; i++)
//                {
//                    System.out.print( viewRows[i] + ","  );
//                }
//                System.out.println();
//                
//                Row[] viewToModel = getViewToModel();
//                modelRows = new int[viewRows.length];
//                
//                System.out.println("selection model");
//                for ( int i=0; i < viewRows.length; i++)
//                {
//                    modelRows[i] = viewToModel[viewRows[i]].modelIndex;
//                    System.out.print( modelRows[i] + ","  );
//                }
//                System.out.println();
//            }
            
            // Something has happened to the data that may have invalidated the row order. 
            clearSortingState();
            fireTableDataChanged();

            //          TODO3 try to find a improved table selection concept while sorting.
//            if ( table != null )
//            {
//                table.getSelectionModel().setValueIsAdjusting( true );
//                table.clearSelection();
//                int[] currSel = table.getSelectedRows();
//                System.out.println("curr selection");
//                for (int i = 0; i < currSel.length; i++)
//                {
//                    System.out.print( currSel[i] + ","  );
//                }
//                System.out.println("selection after");
//                int[] modelToView = getModelToView();
//                int[] selRows = new int[modelRows.length];
//                for ( int i=0; i < modelRows.length; i++)
//                {
//                    System.out.print( modelToView[modelRows[i]] + "," );
//                    selRows[i] = modelToView[modelRows[i]];
//                    table.getSelectionModel().addSelectionInterval( selRows[i], selRows[i] );
//                }
//                System.out.println();
//                table.getSelectionModel().setValueIsAdjusting( false );
//                currSel = table.getSelectedRows();
//                System.out.println("curr selection");
//                for (int i = 0; i < currSel.length; i++)
//                {
//                    System.out.print( currSel[i] + ","  );
//                }
//                
//            }
            
            return;
        }
    }

    private class MouseHandler extends MouseAdapter
    {
        public void mouseClicked(MouseEvent e)
        {
            try
            {
	            int clickCount = e.getClickCount();
	            if ( clickCount != 1 )
	            {
	                return;
	            }
	            if ( isResizingClick( e.getPoint() ) )
	            {
	                return;
	            }
	            
	            JTableHeader h = (JTableHeader) e.getSource();
	            TableColumnModel columnModel = h.getColumnModel();
	            int viewColumn = columnModel.getColumnIndexAtX(e.getX());
	            if ( viewColumn == -1 )
	            {// hit empty column spot.
	                return;
	            }
	            int column = columnModel.getColumn(viewColumn).getModelIndex();
	            if (column != -1)
	            {
	                int status = getSortingStatus(column);
	                if (!e.isControlDown())
	                {
	                    cancelSorting();
	                }
	                // Cycle the sorting states through {NOT_SORTED, ASCENDING, DESCENDING} or 
	                // {NOT_SORTED, DESCENDING, ASCENDING} depending on whether shift is pressed. 
	                status = status + (e.isShiftDown() ? -1 : 1);
	                status = (status + 4) % 3 - 1; // signed mod, returning {-1, 0, 1}
                    h.getTable().clearSelection();
	                setSortingStatus(column, status);
	            }
            }
            catch ( Exception exp )
            {
                NLogger.error( FWSortedTableModel.class, exp, exp );
            }
        }
        
        /**
         * Indicates if the click was inside the resizing box. In this case we
         * ignore it.
         * @param p
         * @return
         */
        public boolean isResizingClick( Point p )
        {
            int column = tableHeader.columnAtPoint( p );
            if (column == -1)
            {
                return false;
            }
            Rectangle r = tableHeader.getHeaderRect(column);
            r.grow(-3, 0);
            if (r.contains(p))
            {
                return false;
            }
            int midPoint = r.x + r.width/2;
            int columnIndex;
            if( tableHeader.getComponentOrientation().isLeftToRight() )
            {
                columnIndex = (p.x < midPoint) ? column - 1 : column;
            }
            else
            {
                columnIndex = (p.x < midPoint) ? column : column - 1;
            }
            if (columnIndex == -1)
            {
                return false;
            }
            return true;
        }
    }

    private static class Arrow implements Icon
    {
        private boolean descending;
        private int size;
        private int priority;

        public Arrow(boolean descending, int size, int priority) {
            this.descending = descending;
            this.size = size;
            this.priority = priority;
        }

        public void paintIcon(Component c, Graphics g, int x, int y)
        {
            Color color = c == null ? Color.gray : c.getBackground();             
            // In a compound sort, make each succesive triangle 20% 
            // smaller than the previous one. 
            int dx = (int)(size/2*Math.pow(0.8, priority));
            int dy = descending ? dx : -dx;
            // Align icon (roughly) with font baseline. 
            y = y + 5*size/7 + (descending ? -dy : 0);
            int shift = descending ? 1 : -1;
            g.translate(x, y);

            // Right diagonal. 
            g.setColor(color.darker());
            g.drawLine(dx / 2, dy, 0, 0);
            g.drawLine(dx / 2, dy + shift, 0, shift);
            
            // Left diagonal. 
            g.setColor(color.brighter());
            g.drawLine(dx / 2, dy, dx, 0);
            g.drawLine(dx / 2, dy + shift, dx, shift);
            
            // Horizontal line. 
            if (descending) 
            {
                g.setColor(color.darker().darker());
            } 
            else 
            {
                g.setColor(color.brighter().brighter());
            }
            g.drawLine(dx, 0, 0, 0);

            g.setColor(color);
            g.translate(-x, -y);
        }

        public int getIconWidth()
        {
            return size;
        }

        public int getIconHeight()
        {
            return size;
        }
    }

    private class SortableHeaderRenderer implements TableCellRenderer
    {
        private TableCellRenderer tableCellRenderer;

        public SortableHeaderRenderer(TableCellRenderer tableCellRenderer)
        {
            this.tableCellRenderer = tableCellRenderer;
        }

        public Component getTableCellRendererComponent(JTable table, Object value,
            boolean isSelected, boolean hasFocus, int row, int column)
        {
            Component c = tableCellRenderer.getTableCellRendererComponent(table, 
                    value, isSelected, hasFocus, row, column);
            if (c instanceof JLabel)
            {
                JLabel l = (JLabel) c;
                l.setHorizontalTextPosition(JLabel.LEFT);
                int modelColumn = table.convertColumnIndexToModel(column);
                l.setIcon(getHeaderRendererIcon(modelColumn, l.getFont().getSize()));
            }
            return c;
        }
    }

    private static class Directive
    {
        private int column;
        private int direction;

        public Directive(int column, int direction)
        {
            this.column = column;
            this.direction = direction;
        }
    }
    
    public static final Comparator REVERSE_COMPARABLE_COMPARATOR = 
        new ReverseComparator();
    
    public static final Comparator LEXICAL_COMPARATOR = new Comparator()
    {
        public int compare(Object o1, Object o2)
        {
            return o1.toString().compareTo(o2.toString());
        }
    };
}
